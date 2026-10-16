package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeaveCacheRepository
import dev.fajar.starter.leave.domain.repositories.LeaveReplicaRepository
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveLeave(
    private val replicas: LeaveReplicaRepository,
    private val cache: LeaveCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) : ObserveCompanyData {
    override val dataSet = CompanyDataSet.Leave

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: LeaveSelection = LeaveSelection(),
    ): Flow<AppResult<LeaveOverview?>> {
        val valid = validateLeaveSelection(selection)
        if (valid is AppResult.Failed) return flowOf(valid)
        return observeAdmitted(workspaceId) { access -> observeReplica(access, selection) }
    }

    override fun observeStatus(workspaceId: String): Flow<AppResult<CompanyDataStatus>> =
        observeAdmitted(workspaceId) { access ->
                replicas.observePublishedCheckpoint(access).map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val checkpoint = result.value
                            when (val ownership = validateStoredLeaveReplica(access, checkpoint)) {
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
        read: (LeaveAccess) -> Flow<AppResult<T>>,
    ): Flow<AppResult<T>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(leaveFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitLeave(
                                            workspaceId,
                                            current.value,
                                            session,
                                            build,
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
                        replicas.observeAdmission(admitted.value).flatMapLatest { admission ->
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: LeaveAccess,
        selection: LeaveSelection,
    ): Flow<AppResult<LeaveOverview?>> =
        replicas.observeDirectory(access).flatMapLatest { result ->
            when (result) {
                is AppResult.Failed -> flowOf(result)
                is AppResult.Success -> {
                    val metadata = result.value
                    val checkpoint = metadata.checkpoint
                    if (checkpoint == null) observeLegacy(access, selection)
                    else
                        when (val owner = validateStoredLeaveReplica(access, checkpoint)) {
                            is AppResult.Failed -> flowOf(owner)
                            is AppResult.Success -> {
                                val directory = requireNotNull(metadata.directory)
                                when (val chosen = selectStoredLeave(directory, selection)) {
                                    is AppResult.Failed -> flowOf(chosen)
                                    is AppResult.Success -> {
                                        val selected = chosen.value
                                        val page =
                                            pageLeaveEmployments(
                                                directory,
                                                selected.employmentAfter,
                                            )
                                        if (selected.employeeId == null)
                                            flowOf(
                                                AppResult.Success(
                                                    LeaveOverview(
                                                        access,
                                                        checkpoint.generation,
                                                        page,
                                                        selected,
                                                        LeavePage(emptyList()),
                                                        LeavePage(emptyList()),
                                                        requireNotNull(checkpoint.refreshedAt),
                                                        checkpoint.refreshAfter,
                                                    )
                                                )
                                            )
                                        else
                                            replicas.observe(access, selected).mapNotNull { queried
                                                ->
                                                when (queried) {
                                                    is AppResult.Failed -> queried
                                                    is AppResult.Success -> {
                                                        val content = queried.value
                                                        // A commit between the two reads must not
                                                        // mix metadata with a different page.
                                                        if (
                                                            content.checkpoint?.generation !=
                                                                checkpoint.generation ||
                                                                content.checkpoint.revision !=
                                                                    checkpoint.revision
                                                        )
                                                            null
                                                        else
                                                            AppResult.Success(
                                                                LeaveOverview(
                                                                    access,
                                                                    checkpoint.generation,
                                                                    page,
                                                                    selected,
                                                                    content.balances,
                                                                    content.requests,
                                                                    requireNotNull(
                                                                        checkpoint.refreshedAt
                                                                    ),
                                                                    checkpoint.refreshAfter,
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
            }
        }

    /** Keeps an existing last-page snapshot readable until the first full replica publishes. */
    private fun observeLegacy(
        access: LeaveAccess,
        selection: LeaveSelection,
    ): Flow<AppResult<LeaveOverview?>> =
        cache.observe(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val ownership = validateStoredLeave(access, result.value)) {
                        is AppResult.Failed -> ownership
                        is AppResult.Success -> {
                            val snapshot = result.value
                            if (selection == LeaveSelection() || snapshot?.selection == selection)
                                result
                            else AppResult.Success(null)
                        }
                    }
            }
        }
}
