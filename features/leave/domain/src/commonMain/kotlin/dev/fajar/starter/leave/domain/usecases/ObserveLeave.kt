package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeaveCacheRepository
import dev.fajar.starter.leave.domain.repositories.LeaveReplicaRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
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
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: LeaveSelection = LeaveSelection(),
    ): Flow<AppResult<LeaveOverview?>> {
        val valid = validateLeaveSelection(selection)
        if (valid is AppResult.Failed) return flowOf(valid)
        return sessions
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
                    is AppResult.Success -> observeReplica(admitted.value, selection)
                }
            }
    }

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
