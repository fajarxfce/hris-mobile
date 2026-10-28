package dev.fajar.starter.inbox.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.inbox.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObservePendingActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ObserveInboxPending(
    private val commands: InboxCommandRepository,
    private val reads: InboxCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) : ObservePendingActions {
    override val group = PendingActionGroup.Inbox

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePending(workspaceId: String): Flow<AppResult<PendingActionSummary>> =
        sessions
            .observe()
            .flatMapLatest { identity ->
                when (identity) {
                    is AppResult.Failed -> flowOf(identity)
                    is AppResult.Success -> {
                        val session = identity.value
                        if (session == null)
                            flowOf(inboxCommandFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { selected ->
                                when (selected) {
                                    is AppResult.Failed -> selected
                                    is AppResult.Success ->
                                        admitInbox(workspaceId, selected.value, session)
                                }
                            }
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { admitted ->
                when (admitted) {
                    is AppResult.Failed -> flowOf(admitted)
                    is AppResult.Success -> {
                        val access = admitted.value
                        reads.observeAdmission(access).flatMapLatest { admission ->
                            when (admission) {
                                is AppResult.Failed -> flowOf(admission)
                                is AppResult.Success -> {
                                    val denial = admission.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else
                                        observeStored(access).map { result ->
                                            val latest = reads.admission(access)
                                            currentCoroutineContext().ensureActive()
                                            when (latest) {
                                                is AppResult.Failed -> latest
                                                is AppResult.Success ->
                                                    latest.value.denial?.let {
                                                        AppResult.Failed(it)
                                                    } ?: result
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun observeStored(access: InboxAccess) =
        commands.observeAll(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> {
                    val rows = result.value
                    if (rows.any { !it.command.belongsTo(access) })
                        inboxCommandFailure("client.inbox_scope_changed", FailureKind.AccessDenied)
                    else
                        AppResult.Success(
                            PendingActionSummary(
                                queued = rows.count { it.outcome is InboxCommandOutcome.Pending },
                                unconfirmed =
                                    rows.count { it.outcome is InboxCommandOutcome.Unconfirmed },
                                needsReview =
                                    rows.count { it.outcome is InboxCommandOutcome.Rejected },
                            )
                        )
                }
            }
        }
}
