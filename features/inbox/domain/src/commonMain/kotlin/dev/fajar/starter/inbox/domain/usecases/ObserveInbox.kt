package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveInbox(
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        query: InboxCacheQuery,
    ): Flow<AppResult<InboxCachePage>> =
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
                        cache.observe(admitted.value, query).flatMapLatest { result ->
                            flow<AppResult<InboxCachePage>> {
                                if (result is AppResult.Failed) {
                                    emit(result)
                                    return@flow
                                }
                                val page = (result as AppResult.Success).value
                                when (
                                    val access =
                                        validateInboxCache(
                                            admitted.value,
                                            page.checkpoint,
                                            clock.now(),
                                        )
                                ) {
                                    is AppResult.Failed -> emit(access)
                                    is AppResult.Success -> {
                                        emit(result)
                                        val deadline =
                                            minOf(
                                                page.checkpoint?.expiresAt
                                                    ?: admitted.value.workspaceExpiresAt,
                                                admitted.value.workspaceExpiresAt,
                                            )
                                        delay(
                                            (deadline - clock.now())
                                                .inWholeMilliseconds
                                                .coerceAtLeast(0)
                                        )
                                        emit(
                                            AppResult.Failed(
                                                Failure(
                                                    FailureKind.AccessDenied,
                                                    "Connect to refresh the inbox.",
                                                    code =
                                                        if (
                                                            admitted.value.workspaceExpiresAt <=
                                                                deadline
                                                        )
                                                            "client.workspace_expired"
                                                        else "client.inbox_cache_expired",
                                                )
                                            )
                                        )
                                    }
                                }
                            }
                        }
                }
            }
}
