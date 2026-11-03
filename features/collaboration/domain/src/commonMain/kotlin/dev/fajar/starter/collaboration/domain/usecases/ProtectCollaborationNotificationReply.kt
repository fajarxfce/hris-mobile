package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRepository
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

/** Finite device protection before session restoration. No account lookup, scheduling or send. */
class ProtectCollaborationNotificationReply(
    private val inputs: CollaborationNotificationInputRepository
) {
    private val submissions = Semaphore(8)

    suspend operator fun invoke(
        token: CollaborationNotificationActionToken,
        text: String,
    ): AppResult<CollaborationNotificationInput> {
        currentCoroutineContext().ensureActive()
        val reply = CollaborationUnverifiedNotificationReply(token, text)
        if (!validCollaborationUnverifiedNotificationReply(reply))
            return unavailable(FailureKind.Validation)
        if (!submissions.tryAcquire()) return unavailable(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) {
                val result = inputs.retain(reply)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        when (val value = result.value) {
                            CollaborationNotificationInputRetention.CapacityExceeded ->
                                collaborationSetupFailure(
                                    "client.collaboration_notification_input_capacity",
                                    FailureKind.Storage,
                                )
                            CollaborationNotificationInputRetention.Contended ->
                                unavailable(FailureKind.Conflict)
                            is CollaborationNotificationInputRetention.Retained -> {
                                if (
                                    !validCollaborationNotificationInput(value.input) ||
                                        value.input.reply != reply
                                )
                                    unavailable(FailureKind.Conflict)
                                else AppResult.Success(value.input)
                            }
                        }
                }
            } ?: unavailable(FailureKind.Timeout)
        } finally {
            submissions.release()
        }
    }

    private fun unavailable(kind: FailureKind) =
        collaborationSetupFailure(
            "client.collaboration_notification_input_protection_unavailable",
            kind,
        )
}
