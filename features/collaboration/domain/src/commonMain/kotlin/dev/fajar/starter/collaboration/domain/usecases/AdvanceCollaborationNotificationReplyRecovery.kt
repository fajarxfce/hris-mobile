package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyRecoveryCursorRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Advances an exact account cursor before attempting one selected reply, without company IO. */
class AdvanceCollaborationNotificationReplyRecovery(
    private val cursors: CollaborationNotificationReplyRecoveryCursorRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        work: CollaborationNotificationReplyRecoveryWork,
        reference: CollaborationNotificationReplyRecoveryReference,
        expectedCursor: CollaborationNotificationReplyRecoveryCursor? = work.cursor,
    ): AppResult<CollaborationNotificationReplyRecoveryCursor> {
        currentCoroutineContext().ensureActive()
        if (!validCollaborationNotificationReplyRecoveryWork(work) || reference !in work.entries)
            return failure(FailureKind.Validation)
        if (
            expectedCursor != work.cursor &&
                (expectedCursor == null ||
                    !validCollaborationNotificationReplyRecoveryCursor(expectedCursor) ||
                    work.entries.none {
                        it.sequence == expectedCursor.afterSequence &&
                            it.sequence < reference.sequence
                    })
        )
            return failure(FailureKind.Validation)
        val cycle = expectedCursor?.cycle
        if (
            cycle?.id != work.cursor?.cycle?.id ||
                cycle?.finished == true ||
                (expectedCursor != work.cursor && cycle?.initial == true)
        )
            return failure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { advance(work, reference, expectedCursor) }
            ?: failure(FailureKind.Timeout)
    }

    private suspend fun advance(
        work: CollaborationNotificationReplyRecoveryWork,
        reference: CollaborationNotificationReplyRecoveryReference,
        expectedCursor: CollaborationNotificationReplyRecoveryCursor?,
    ): AppResult<CollaborationNotificationReplyRecoveryCursor> {
        val parent = work.parent
        guard(parent).let { if (it is AppResult.Failed) return it }
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val cycle =
            expectedCursor?.cycle?.let {
                it.copy(
                    initial = false,
                    inFlight = true,
                    retryRequired = it.retryRequired || it.inFlight,
                )
            }
        val written = cursors.replace(access, expectedCursor, reference.sequence, cycle)
        guard(parent).let { if (it is AppResult.Failed) return it }
        if (written is AppResult.Failed) return written
        val cursor = (written as AppResult.Success).value ?: return failure(FailureKind.Conflict)
        if (
            !validCollaborationNotificationReplyRecoveryCursor(cursor) ||
                cursor.afterSequence != reference.sequence ||
                cursor.generation == expectedCursor?.generation ||
                cursor.cycle != cycle
        )
            return failure(FailureKind.Conflict)
        return AppResult.Success(cursor)
    }

    private suspend fun guard(parent: CollaborationNativeParent) =
        sessions.guardPushParent(server, build, parent)

    private fun failure(kind: FailureKind) =
        collaborationSetupFailure("client.collaboration_notification_reply_seek_unavailable", kind)
}
