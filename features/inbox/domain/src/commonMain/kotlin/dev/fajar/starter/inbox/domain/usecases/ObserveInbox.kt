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
                                        page.checkpoint?.let {
                                            delay(
                                                (it.expiresAt - clock.now())
                                                    .inWholeMilliseconds
                                                    .coerceAtLeast(0)
                                            )
                                            emit(
                                                AppResult.Failed(
                                                    Failure(
                                                        FailureKind.AccessDenied,
                                                        "Connect to refresh the inbox.",
                                                        code = "client.inbox_cache_expired",
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
}
