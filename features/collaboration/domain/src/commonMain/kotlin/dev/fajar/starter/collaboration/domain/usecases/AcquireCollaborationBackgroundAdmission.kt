package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Acquires fresh company admission without reading or changing the foreground workspace. */
class AcquireCollaborationBackgroundAdmission(
    private val recovery: CollaborationRecoveryRepository,
    private val lists: CollaborationListRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        parent: CollaborationNativeParent,
        device: CollaborationDeviceReference,
    ): AppResult<CollaborationBackgroundAdmission> {
        if (
            !validCollaborationMessageId(device.companyId) ||
                !validCollaborationMessageId(device.deviceId) ||
                device.version < 0
        )
            return collaborationRecoveryFailure(
                "client.collaboration_invalid_request",
                FailureKind.Validation,
            )
        if (
            device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return collaborationRecoveryFailure(
                "client.collaboration_device_unavailable",
                FailureKind.Unavailable,
            )
        if (!execution.tryLock())
            return collaborationRecoveryFailure(
                "client.collaboration_recovery_busy",
                FailureKind.Unavailable,
            )
        try {
            return withTimeoutOrNull(15_000) { acquire(parent, device) }
                ?: collaborationRecoveryFailure(
                    "client.collaboration_recovery_timeout",
                    FailureKind.Timeout,
                )
        } finally {
            execution.unlock()
        }
    }

    private suspend fun acquire(
        parent: CollaborationNativeParent,
        device: CollaborationDeviceReference,
    ): AppResult<CollaborationBackgroundAdmission> {
        val initial = capture(parent)
        if (initial is AppResult.Failed) return initial
        val requestedAt = clock.now()
        val owner = parent.owner(device.companyId)
        val received = recovery.admission(owner)
        val rejected = (received as? AppResult.Failed)?.failure
        if (rejected != null && collaborationContentResponseClosesRead(rejected))
            withContext(NonCancellable) {
                withTimeoutOrNull(2_000) { lists.restrict(owner.localListAccess(), rejected) }
            }
        currentCoroutineContext().ensureActive()
        val current = capture(parent)
        currentCoroutineContext().ensureActive()
        if (received is AppResult.Failed) return received
        if (current is AppResult.Failed) return current
        val session = (current as AppResult.Success).value
        return admitCollaborationBackgroundObservation(
            (received as AppResult.Success).value,
            parent,
            device,
            requestedAt,
            clock.now(),
            Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
        )
    }

    private suspend fun capture(parent: CollaborationNativeParent): AppResult<Session> {
        val captured = sessions.current()
        if (captured is AppResult.Failed) return captured
        val session = (captured as AppResult.Success).value
        val current = collaborationNativeParent(session, server, build, clock.now())
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != parent)
            return collaborationRecoveryFailure("client.session_changed", FailureKind.Unauthorized)
        return AppResult.Success(requireNotNull(session))
    }
}
