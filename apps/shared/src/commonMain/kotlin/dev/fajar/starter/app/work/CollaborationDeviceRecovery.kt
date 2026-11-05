package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.*

/**
 * Synchronizes the enrolled device even before a provider room or outgoing message exists. The SDK
 * can publish its encryption keys; recipient readiness remains a server decision.
 */
class CollaborationDeviceRecovery(
    private val acquire: AcquireCollaborationBackgroundRuntime,
    private val synchronize: SynchronizeCollaborationDevice,
    private val observeAccess: ObserveCollaborationAccess,
) {
    suspend operator fun invoke(admission: CollaborationBackgroundAdmission): SyncResult {
        var lease: CollaborationBackgroundRuntimeLease? = null
        try {
            val acquired = acquire(admission)
            if (acquired is AppResult.Success) lease = acquired.value
            currentCoroutineContext().ensureActive()
            if (acquired is AppResult.Failed) return collaborationWorkFailure(acquired.failure)
            val owned =
                lease
                    ?: return SyncResult.Blocked(
                        Failure(
                            FailureKind.Unavailable,
                            "Chat setup is required.",
                            code = "client.collaboration_setup_required",
                        )
                    )
            val access = admission.owner.localListAccess()
            return monitorCollaborationBackgroundDelivery(
                owned,
                observeAccess(CollaborationLocalScope.Captured(access)),
                access,
            ) {
                when (
                    val result =
                        synchronize(
                            CollaborationOperationScope.Background(admission),
                            admission.observation.deviceId,
                        )
                ) {
                    is AppResult.Success -> SyncResult.Complete
                    is AppResult.Failed -> collaborationWorkFailure(result.failure)
                }
            }
        } finally {
            withContext(NonCancellable) { lease?.close() }
        }
    }
}
