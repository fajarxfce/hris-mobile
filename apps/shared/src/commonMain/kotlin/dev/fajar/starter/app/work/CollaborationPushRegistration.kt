package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.sync.domain.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

/** Registers one admitted installed identity; never enables notifications or enrolls a device. */
class CollaborationPushRegistration(
    private val status: (String) -> Flow<AppResult<NativePushStatus>>,
    private val acquire:
        suspend (CollaborationBackgroundAdmission) -> AppResult<
                CollaborationBackgroundRuntimeLease?
            >,
    private val access: (CollaborationLocalAccess) -> Flow<AppResult<CollaborationLocalAccess>>,
    private val activate: suspend (CollaborationOperationScope, String) -> AppResult<Unit>,
) {
    private val execution = Mutex()

    suspend operator fun invoke(admission: CollaborationBackgroundAdmission): SyncResult {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return retry("busy")
        try {
            return withTimeoutOrNull(15.seconds) { register(admission) } ?: retry("timeout")
        } finally {
            execution.unlock()
        }
    }

    private suspend fun register(admission: CollaborationBackgroundAdmission): SyncResult {
        readiness(admission.owner.sessionId)?.let {
            return it
        }
        var lease: CollaborationBackgroundRuntimeLease? = null
        var closed: AppResult<Unit> = AppResult.Success(Unit)
        val result =
            try {
                val borrowed = acquire(admission)
                if (borrowed is AppResult.Success) lease = borrowed.value
                currentCoroutineContext().ensureActive()
                val owned = lease
                when {
                    borrowed is AppResult.Failed -> collaborationWorkFailure(borrowed.failure)
                    owned == null -> SyncResult.Blocked(failure("setup_required"))
                    owned.owner != admission.owner ||
                        owned.deviceId != admission.observation.deviceId ->
                        SyncResult.Blocked(failure("owner_changed", FailureKind.Unauthorized))
                    else -> readiness(admission.owner.sessionId) ?: publish(admission, owned)
                }
            } finally {
                closed =
                    withContext(NonCancellable) {
                        withTimeoutOrNull(2.seconds) { lease?.close() ?: AppResult.Success(Unit) }
                            ?: AppResult.Failed(failure("close_timeout", FailureKind.Timeout))
                    }
            }
        currentCoroutineContext().ensureActive()
        val failure = (closed as? AppResult.Failed)?.failure
        return if (failure != null)
            combineCollaborationRecovery(result, collaborationWorkFailure(failure))
        else result
    }

    private suspend fun publish(
        admission: CollaborationBackgroundAdmission,
        lease: CollaborationBackgroundRuntimeLease,
    ): SyncResult {
        val expected = admission.owner.localListAccess()
        return monitorCollaborationBackgroundDelivery(lease, access(expected), expected) {
            val result = activate(CollaborationOperationScope.Background(admission), lease.deviceId)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Success -> SyncResult.Complete
                is AppResult.Failed -> collaborationWorkFailure(result.failure)
            }
        }
    }

    /** Null means a current enabled native registration is available for managed chat push. */
    private suspend fun readiness(sessionId: String): SyncResult? {
        val observed = status(sessionId).first()
        currentCoroutineContext().ensureActive()
        if (observed is AppResult.Failed) return collaborationWorkFailure(observed.failure)
        val value = (observed as AppResult.Success).value
        if (!value.supported || !value.enabled || value.access != NotificationAccess.Granted)
            return SyncResult.Complete
        return if (!value.registered || value.pending) retry("pending") else null
    }

    private fun retry(code: String) = SyncResult.Retry(failure(code))

    private fun failure(code: String, kind: FailureKind = FailureKind.Unavailable) =
        Failure(
            kind,
            "Chat notification registration is pending.",
            code = "client.collaboration_push_$code",
        )
}
