package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*

/** Exact attempt transitions. Progress is retained before execution and uncertainty survives. */
class AdvanceCollaborationNotificationInputRecovery(
    private val cursors: CollaborationNotificationInputRecoveryRepository,
    private val inputs: CollaborationNotificationInputRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    suspend operator fun invoke(
        snapshot: CollaborationNotificationInputRecoverySnapshot,
        change: CollaborationNotificationInputRecoveryChange,
    ): AppResult<CollaborationNotificationInputRecoverySnapshot> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationNotificationInputRecoveryCursor(snapshot.cursor) ||
                snapshot.cursor.state.finished
        )
            return notificationInputRecoveryFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) { advance(snapshot, change) }
            ?: notificationInputRecoveryFailure(FailureKind.Timeout)
    }

    private suspend fun advance(
        snapshot: CollaborationNotificationInputRecoverySnapshot,
        change: CollaborationNotificationInputRecoveryChange,
    ): AppResult<CollaborationNotificationInputRecoverySnapshot> {
        val parent = snapshot.parent
        val cursor = snapshot.cursor
        val state = cursor.state
        val next =
            when (change) {
                is CollaborationNotificationInputRecoveryChange.Attempt -> {
                    if (state.references.getOrNull(state.nextIndex) != change.reference)
                        return notificationInputRecoveryFailure(FailureKind.Validation)
                    state.copy(
                        nextIndex = state.nextIndex + 1,
                        inFlight = true,
                        retryRequired = state.retryRequired || state.inFlight,
                    )
                }
                is CollaborationNotificationInputRecoveryChange.Settle -> {
                    if (!state.inFlight)
                        return notificationInputRecoveryFailure(FailureKind.Validation)
                    state.copy(
                        inFlight = false,
                        retryRequired = state.retryRequired || change.retryRequired,
                    )
                }
                CollaborationNotificationInputRecoveryChange.Finish -> {
                    if (state.nextIndex != state.references.size)
                        return notificationInputRecoveryFailure(FailureKind.Validation)
                    guard(parent).let { if (it is AppResult.Failed) return it }
                    val found =
                        cursors.current(
                            CollaborationRecoveryAccess(
                                parent.sessionId,
                                parent.audience,
                                parent.accountId,
                            )
                        )
                    guard(parent).let { if (it is AppResult.Failed) return it }
                    if (found is AppResult.Failed) return found
                    if ((found as AppResult.Success).value != cursor)
                        return notificationInputRecoveryFailure(FailureKind.Conflict)
                    val current = readNotificationInputReferences(inputs) { guard(parent) }
                    if (current is AppResult.Failed) return current
                    state.copy(
                        inFlight = false,
                        retryRequired = state.retryRequired || state.inFlight,
                        continuationRequired =
                            (current as AppResult.Success).value.any { it !in state.references },
                        finished = true,
                    )
                }
            }
        guard(parent).let { if (it is AppResult.Failed) return it }
        val stored =
            cursors.replace(
                CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId),
                cursor,
                next,
            )
        guard(parent).let { if (it is AppResult.Failed) return it }
        return notificationInputRecoveryResult(parent, cursor, next, stored)
    }

    private suspend fun guard(parent: CollaborationNativeParent) =
        sessions.guardPushParent(server, build, parent)
}
