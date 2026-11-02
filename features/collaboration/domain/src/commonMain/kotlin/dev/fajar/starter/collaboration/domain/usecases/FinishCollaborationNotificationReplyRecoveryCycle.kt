package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Proves an empty tail before closing the exact sweep, retaining any unsettled retry outcome. */
class FinishCollaborationNotificationReplyRecoveryCycle(
    private val cursors: CollaborationNotificationReplyRecoveryCursorRepository,
    private val inputs: CollaborationNotificationReplyRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        snapshot: CollaborationNotificationReplyRecoverySnapshot
    ): AppResult<CollaborationNotificationReplyRecoverySnapshot> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationReplyRecoveryCursor(snapshot.cursor) ||
                snapshot.cursor.cycle == null ||
                snapshot.cursor.cycle.finished
        )
            return notificationReplyCycleFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { finish(snapshot) }
            ?: notificationReplyCycleFailure(FailureKind.Timeout)
    }

    private suspend fun finish(
        snapshot: CollaborationNotificationReplyRecoverySnapshot
    ): AppResult<CollaborationNotificationReplyRecoverySnapshot> {
        val parent = snapshot.parent
        val cursor = snapshot.cursor
        val cycle = requireNotNull(cursor.cycle)
        guard(parent).let { if (it is AppResult.Failed) return it }
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val found = cursors.current(access)
        guard(parent).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        if ((found as AppResult.Success).value != cursor)
            return notificationReplyCycleFailure(FailureKind.Conflict)
        val tail = inputs.page(access, if (cycle.initial) null else cursor.afterSequence, limit = 1)
        guard(parent).let { if (it is AppResult.Failed) return it }
        if (tail is AppResult.Failed) return tail
        val page = (tail as AppResult.Success).value
        if (page.inputs.isNotEmpty() || page.nextAfter != null)
            return notificationReplyCycleFailure(FailureKind.Conflict)
        val completed =
            cycle.copy(
                initial = false,
                inFlight = false,
                retryRequired = cycle.retryRequired || cycle.inFlight,
                finished = true,
            )
        val stored = cursors.replace(access, cursor, cursor.afterSequence, completed)
        guard(parent).let { if (it is AppResult.Failed) return it }
        return notificationReplyCycleResult(parent, cursor, cursor.afterSequence, completed, stored)
    }

    private suspend fun guard(parent: CollaborationNativeParent) =
        sessions.guardPushParent(server, build, parent)
}
