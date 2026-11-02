package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Current admitted company work borrows a finite reference, independently of screen selection. */
class AcquireCollaborationBackgroundRuntime(
    private val runtimes: CollaborationRuntimeRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val acquisition = Mutex()

    suspend operator fun invoke(
        admission: CollaborationBackgroundAdmission
    ): AppResult<CollaborationBackgroundRuntimeLease?> {
        if (!acquisition.tryLock()) return failed("client.collaboration_runtime_busy")
        try {
            return acquire(admission)
        } finally {
            acquisition.unlock()
        }
    }

    private suspend fun acquire(
        admission: CollaborationBackgroundAdmission
    ): AppResult<CollaborationBackgroundRuntimeLease?> {
        val captured = admit(admission)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val remaining =
            minOf(
                (admission.expiresAt - clock.now()).inWholeMilliseconds,
                (-admission.monotonicDeadline.elapsedNow()).inWholeMilliseconds,
            )
        if (remaining !in 1..60_000) return failed("client.collaboration_revalidation_required")
        var opened: CollaborationBackgroundRuntimeLease? = null
        var transferred = false
        try {
            val result =
                runtimes.acquireBackground(owner, admission.observation.deviceId, remaining)
            if (result is AppResult.Success) opened = result.value
            val current = requireCollaborationOwner(admit(admission), owner, result)
            if (current is AppResult.Failed)
                return AppResult.Failed(
                    retainCollaborationCommandFailure(
                        opened?.credentialFailures?.value,
                        current.failure,
                    )
                )
            if (result is AppResult.Failed) return result
            val lease = opened ?: return AppResult.Success(null)
            if (lease.owner != owner || lease.deviceId != admission.observation.deviceId)
                return collaborationSetupFailure("client.session_changed", FailureKind.Unauthorized)
            lease.credentialFailures.value?.let {
                return AppResult.Failed(it)
            }
            lease.interruptions.value?.let {
                return AppResult.Failed(it)
            }
            currentCoroutineContext().ensureActive()
            val published = lease.publish()
            if (published is AppResult.Failed) return published
            transferred = true
            return AppResult.Success(lease)
        } finally {
            if (!transferred) withContext(NonCancellable) { opened?.close() }
        }
    }

    private suspend fun admit(
        admission: CollaborationBackgroundAdmission
    ): AppResult<CollaborationOwner> {
        val captured = sessions.captureCollaborationBackgroundOwner(admission, server, build, clock)
        if (captured is AppResult.Failed) return captured
        val owner = (captured as AppResult.Success).value
        val gate = lists.admission(owner.localListAccess())
        currentCoroutineContext().ensureActive()
        if (gate is AppResult.Success)
            gate.value.denial?.let {
                return AppResult.Failed(it)
            }
        val current =
            requireCollaborationOwner(
                sessions.captureCollaborationBackgroundOwner(admission, server, build, clock),
                owner,
                gate,
            )
        if (current is AppResult.Failed) return current
        if (gate is AppResult.Failed) return gate
        return AppResult.Success(owner)
    }

    private fun failed(code: String) = collaborationRecoveryFailure(code, FailureKind.Unavailable)
}
