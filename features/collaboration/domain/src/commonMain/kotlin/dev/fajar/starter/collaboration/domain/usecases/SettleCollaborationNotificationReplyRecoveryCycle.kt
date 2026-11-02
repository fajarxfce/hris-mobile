package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationReplyRecoveryCursorRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Records one exact attempt outcome; a healthy later input cannot clear a prior retry. */
class SettleCollaborationNotificationReplyRecoveryCycle(
    private val cursors: CollaborationNotificationReplyRecoveryCursorRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        snapshot: CollaborationNotificationReplyRecoverySnapshot,
        retryRequired: Boolean,
    ): AppResult<CollaborationNotificationReplyRecoverySnapshot> {
        currentCoroutineContext().ensureActive()
        val cursor = snapshot.cursor
        val cycle = cursor.cycle
        if (
            !validCollaborationNotificationReplyRecoveryCursor(cursor) ||
                cycle == null ||
                cycle.initial ||
                !cycle.inFlight ||
                cycle.finished
        )
            return notificationReplyCycleFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
            settle(
                snapshot,
                cycle.copy(inFlight = false, retryRequired = cycle.retryRequired || retryRequired),
            )
        } ?: notificationReplyCycleFailure(FailureKind.Timeout)
    }

    private suspend fun settle(
        snapshot: CollaborationNotificationReplyRecoverySnapshot,
        cycle: CollaborationNotificationReplyRecoveryCycle,
    ): AppResult<CollaborationNotificationReplyRecoverySnapshot> {
        val parent = snapshot.parent
        val cursor = snapshot.cursor
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val stored = cursors.replace(access, cursor, cursor.afterSequence, cycle)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        return notificationReplyCycleResult(parent, cursor, cursor.afterSequence, cycle, stored)
    }
}
