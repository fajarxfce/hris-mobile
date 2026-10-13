package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveInbox(
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
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
                                        admitInbox(workspaceId, workspace.value, session)
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
                        cache.observe(admitted.value, query).map { result ->
                            when (result) {
                                is AppResult.Failed -> result
                                is AppResult.Success ->
                                    when (
                                        val ownership =
                                            validateStoredInbox(
                                                admitted.value,
                                                result.value.checkpoint,
                                            )
                                    ) {
                                        is AppResult.Failed -> ownership
                                        is AppResult.Success -> result
                                    }
                            }
                        }
                }
            }
}
