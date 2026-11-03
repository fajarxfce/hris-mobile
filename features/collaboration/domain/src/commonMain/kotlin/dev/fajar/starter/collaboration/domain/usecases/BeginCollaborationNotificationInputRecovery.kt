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

/** Resumes an active finite snapshot or retains a new one without opening any device input. */
class BeginCollaborationNotificationInputRecovery(
    private val cursors: CollaborationNotificationInputRecoveryRepository,
    private val inputs: CollaborationNotificationInputRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        expectedParent: CollaborationNativeParent? = null
    ): AppResult<CollaborationNotificationInputRecoverySnapshot?> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(8_000) { begin(expectedParent) }
            ?: notificationInputRecoveryFailure(FailureKind.Timeout)
    }

    private suspend fun begin(
        expectedParent: CollaborationNativeParent?
    ): AppResult<CollaborationNotificationInputRecoverySnapshot?> {
        val captured = sessions.capturePushParent(server, build)
        if (captured is AppResult.Failed) return captured
        val parent = (captured as AppResult.Success).value
        if (expectedParent != null && parent != expectedParent)
            return notificationInputRecoveryFailure(FailureKind.Unauthorized)
        val access =
            CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        val found = cursors.current(access)
        guard(parent).let { if (it is AppResult.Failed) return it }
        if (found is AppResult.Failed) return found
        val cursor = (found as AppResult.Success).value
        if (cursor != null && !validCollaborationNotificationInputRecoveryCursor(cursor))
            return notificationInputRecoveryFailure(FailureKind.Conflict)
        if (cursor != null && !cursor.state.finished)
            return AppResult.Success(CollaborationNotificationInputRecoverySnapshot(parent, cursor))
        val foundReferences = readNotificationInputReferences(inputs) { guard(parent) }
        if (foundReferences is AppResult.Failed) return foundReferences
        val references = (foundReferences as AppResult.Success).value
        if (references.isEmpty()) return AppResult.Success(null)
        val state =
            CollaborationNotificationInputRecoveryState(Uuid.random().toString(), references)
        val stored = cursors.replace(access, cursor, state)
        guard(parent).let { if (it is AppResult.Failed) return it }
        return notificationInputRecoveryResult(parent, cursor, state, stored)
    }

    private suspend fun guard(parent: CollaborationNativeParent) =
        sessions.guardPushParent(server, build, parent)
}
