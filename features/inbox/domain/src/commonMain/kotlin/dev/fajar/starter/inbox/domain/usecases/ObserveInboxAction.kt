package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.InboxCommandRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveInboxAction(
    private val commands: InboxCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(workspaceId: String, itemId: String): Flow<AppResult<InboxCommandRecord?>> =
        combine(sessions.observe(), workspaces.observe()) { session, workspace ->
                when {
                    session is AppResult.Failed -> session
                    workspace is AppResult.Failed -> workspace
                    else ->
                        admitInbox(
                            workspaceId,
                            (workspace as AppResult.Success).value,
                            (session as AppResult.Success).value,
                            clock.now(),
                        )
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success ->
                        commands.observe(admitted.value, itemId).flatMapLatest { result ->
                            flow<AppResult<InboxCommandRecord?>> {
                                val remaining = admitted.value.sessionExpiresAt - clock.now()
                                if (remaining.isPositive()) {
                                    emit(result)
                                    delay(remaining)
                                }
                                emit(
                                    inboxCommandFailure("session_revoked", FailureKind.Unauthorized)
                                )
                            }
                        }
                }
            }
}
