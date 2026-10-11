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
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(inboxCommandFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { workspace ->
                                when (workspace) {
                                    is AppResult.Failed -> workspace
                                    is AppResult.Success ->
                                        admitInbox(
                                            workspaceId,
                                            workspace.value,
                                            session,
                                            clock.now(),
                                        )
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success ->
                        commands.observe(admitted.value, itemId).flatMapLatest { result ->
                            flow<AppResult<InboxCommandRecord?>> {
                                val deadline =
                                    minOf(
                                        admitted.value.sessionExpiresAt,
                                        admitted.value.workspaceExpiresAt,
                                    )
                                val remaining = deadline - clock.now()
                                if (remaining.isPositive()) {
                                    emit(result)
                                    delay(remaining)
                                }
                                emit(
                                    if (
                                        admitted.value.sessionExpiresAt <=
                                            admitted.value.workspaceExpiresAt
                                    )
                                        inboxCommandFailure(
                                            "session_revoked",
                                            FailureKind.Unauthorized,
                                        )
                                    else
                                        inboxCommandFailure(
                                            "client.workspace_expired",
                                            FailureKind.AccessDenied,
                                        )
                                )
                            }
                        }
                }
            }
}
