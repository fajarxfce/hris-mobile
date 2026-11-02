package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Discovers only opaque account-owned IDs; company text requires its separate read admission. */
class ReadCollaborationNotificationReplyRecovery(
    private val inputs: CollaborationNotificationReplyRepository,
    private val cursors: CollaborationNotificationReplyRecoveryCursorRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(): AppResult<CollaborationNotificationReplyRecoveryWork?> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(8_000) { read() } ?: failure(FailureKind.Timeout)
    }

    private suspend fun read(): AppResult<CollaborationNotificationReplyRecoveryWork?> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val position = cursors.current(access)
        guard(parent).let { if (it is AppResult.Failed) return it }
        if (position is AppResult.Failed) return position
        val cursor = (position as AppResult.Success).value
        if (cursor != null && !validCollaborationNotificationReplyRecoveryCursor(cursor))
            return failure(FailureKind.Conflict)
        if (cursor?.cycle?.finished == true) return failure(FailureKind.Conflict)
        var after = if (cursor?.cycle?.initial == true) null else cursor?.afterSequence
        var page = inputs.page(access, after, COLLABORATION_NOTIFICATION_REPLY_RECOVERY_LIMIT)
        guard(parent).let { if (it is AppResult.Failed) return it }
        if (page is AppResult.Failed) return page
        var value = (page as AppResult.Success).value
        if (value.inputs.isEmpty() && value.nextAfter != null) return failure(FailureKind.Conflict)
        if (value.inputs.isEmpty() && after != null && cursor?.cycle == null) {
            after = null
            page = inputs.page(access, null, COLLABORATION_NOTIFICATION_REPLY_RECOVERY_LIMIT)
            guard(parent).let { if (it is AppResult.Failed) return it }
            if (page is AppResult.Failed) return page
            value = (page as AppResult.Success).value
        }
        if (value.inputs.isEmpty())
            return if (value.nextAfter == null) AppResult.Success(null)
            else failure(FailureKind.Conflict)
        // Validate every record before stripping private company content from the returned work.
        if (
            value.inputs.any {
                !validCollaborationNotificationReplyInput(it) ||
                    it.review != null ||
                    it.candidate.parent.audience != parent.audience ||
                    it.candidate.parent.accountId != parent.accountId
            }
        )
            return failure(FailureKind.Conflict)
        val work =
            CollaborationNotificationReplyRecoveryWork(
                parent,
                cursor,
                after,
                value.inputs.map {
                    CollaborationNotificationReplyRecoveryReference(it.id, it.sequence)
                },
                value.nextAfter != null,
            )
        if (
            !validCollaborationNotificationReplyRecoveryWork(work) ||
                (value.nextAfter != null && value.nextAfter != work.entries.last().sequence)
        )
            return failure(FailureKind.Conflict)
        return AppResult.Success(work)
    }

    private suspend fun guard(parent: CollaborationNativeParent) =
        sessions.guardPushParent(server, build, parent)

    private fun failure(kind: FailureKind) =
        collaborationSetupFailure(
            "client.collaboration_notification_reply_discovery_unavailable",
            kind,
        )
}
