package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveInbox(
    private val cache: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) : ObserveCompanyData {
    override val dataSet = CompanyDataSet.Inbox

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        query: InboxCacheQuery,
    ): Flow<AppResult<InboxCachePage>> =
        observeAdmitted(workspaceId) { access -> observeStored(access, query) }

    override fun observeStatus(workspaceId: String): Flow<AppResult<CompanyDataStatus>> =
        observeAdmitted(workspaceId) { access ->
                cache.observePublishedCheckpoint(access).map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val checkpoint = result.value
                            when (val ownership = validateStoredInbox(access, checkpoint)) {
                                is AppResult.Failed -> ownership
                                is AppResult.Success ->
                                    AppResult.Success(CompanyDataStatus(checkpoint?.refreshedAt))
                            }
                        }
                    }
                }
            }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observeAdmitted(
        workspaceId: String,
        read: (InboxAccess) -> Flow<AppResult<T>>,
    ): Flow<AppResult<T>> =
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
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitInbox(workspaceId, current.value, session)
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
                        cache.observeAdmission(admitted.value).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else read(admitted.value)
                                }
                            }
                        }
                }
            }
            .buffer(0)

    private fun observeStored(access: InboxAccess, query: InboxCacheQuery) =
        cache.observe(access, query).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val ownership = validateStoredInbox(access, result.value.checkpoint)) {
                        is AppResult.Failed -> ownership
                        is AppResult.Success -> result
                    }
            }
        }
}
