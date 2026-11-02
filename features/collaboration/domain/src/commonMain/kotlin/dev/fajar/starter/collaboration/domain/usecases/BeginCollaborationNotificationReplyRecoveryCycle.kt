@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** Keeps an interrupted active sweep; every new sweep starts from the minimum input sequence. */
class BeginCollaborationNotificationReplyRecoveryCycle(
    private val cursors: CollaborationNotificationReplyRecoveryCursorRepository,
    private val inputs: CollaborationNotificationReplyRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(): AppResult<CollaborationNotificationReplyRecoverySnapshot?> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(8_000) { begin() }
            ?: notificationReplyCycleFailure(FailureKind.Timeout)
    }

    private suspend fun begin(): AppResult<CollaborationNotificationReplyRecoverySnapshot?> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val found = cursors.current(access)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (found is AppResult.Failed) return found
        val cursor = (found as AppResult.Success).value
        if (cursor != null && !validCollaborationNotificationReplyRecoveryCursor(cursor))
            return notificationReplyCycleFailure(FailureKind.Conflict)
        if (cursor?.cycle?.finished == false)
            return AppResult.Success(CollaborationNotificationReplyRecoverySnapshot(parent, cursor))
        // A fresh account with no retained replies needs neither a history key nor a new cursor.
        val pending = inputs.page(access, limit = 1)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        if (pending is AppResult.Failed) return pending
        val page = (pending as AppResult.Success).value
        if (page.inputs.size > 1) return notificationReplyCycleFailure(FailureKind.Conflict)
        val first = page.inputs.firstOrNull()
        if (first == null)
            return if (page.nextAfter == null) AppResult.Success(null)
            else notificationReplyCycleFailure(FailureKind.Conflict)
        if (
            !validCollaborationNotificationReplyInput(first) ||
                first.review != null ||
                first.candidate.parent.audience != parent.audience ||
                first.candidate.parent.accountId != parent.accountId ||
                (page.nextAfter != null && page.nextAfter != first.sequence)
        )
            return notificationReplyCycleFailure(FailureKind.Conflict)
        val cycle =
            CollaborationNotificationReplyRecoveryCycle(
                Uuid.random().toString(),
                initial = true,
                inFlight = false,
                retryRequired = false,
                finished = false,
            )
        val after = cursor?.afterSequence ?: 0
        val stored = cursors.replace(access, cursor, after, cycle)
        sessions.guardPushParent(server, build, parent).let {
            if (it is AppResult.Failed) return it
        }
        return notificationReplyCycleResult(parent, cursor, after, cycle, stored)
    }
}
