package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** Authenticates the current account and proves its durable copy before exact device cleanup. */
class TransferCollaborationNotificationInput(
    private val protectedInputs: CollaborationNotificationInputRepository,
    private val actions: CollaborationNotificationActionRepository,
    private val replies: CollaborationNotificationReplyRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        reference: CollaborationNotificationInputReference
    ): AppResult<CollaborationNotificationInputTransfer> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationInputReference(reference))
            return unavailable(FailureKind.Validation)
        if (!execution.tryLock()) return unavailable(FailureKind.Unavailable)
        return try {
            withTimeoutOrNull(8_000) { transfer(reference) } ?: unavailable(FailureKind.Timeout)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun transfer(
        reference: CollaborationNotificationInputReference
    ): AppResult<CollaborationNotificationInputTransfer> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val found = protectedInputs.get(reference)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (found is AppResult.Failed) return found
        val input =
            (found as AppResult.Success).value
                ?: return AppResult.Success(CollaborationNotificationInputTransfer.Absent)
        if (!validCollaborationNotificationInput(input) || input.reference != reference)
            return unavailable(FailureKind.Conflict)

        val retained =
            retainAuthenticatedNotificationReply(
                parent,
                input.reply.token,
                input.reply.text,
                actions,
                replies,
                sessions,
                server,
                build,
            )
        if (retained is AppResult.Failed) return retained
        val expected = (retained as AppResult.Success).value
        // A returned retention receipt alone is insufficient: reproduce the exact stored input.
        val copied =
            replies.get(
                CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId),
                expected.id,
            )
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (copied is AppResult.Failed) return copied
        if ((copied as AppResult.Success).value != expected)
            return unavailable(FailureKind.Conflict)

        val removed = protectedInputs.remove(input)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (removed is AppResult.Failed) return removed
        if (!(removed as AppResult.Success).value) return unavailable(FailureKind.Conflict)
        return AppResult.Success(CollaborationNotificationInputTransfer.Transferred(expected.id))
    }

    private fun unavailable(kind: FailureKind) =
        collaborationSetupFailure(
            "client.collaboration_notification_input_transfer_unavailable",
            kind,
        )
}
