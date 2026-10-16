package dev.fajar.starter.workforce.domain.overtime.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeCacheRepository
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeReplicaRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveOvertime(
    private val replicas: OvertimeReplicaRepository,
    private val cache: OvertimeCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        selection: OvertimeSelection = OvertimeSelection(),
    ): Flow<AppResult<OvertimeSnapshot<OvertimeOverview>?>> {
        val valid = validateOvertimeSelection(selection)
        if (valid is AppResult.Failed) return flowOf(valid)
        return sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(overtimeFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitOvertime(
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
                        replicas.observeAdmission(admitted.value).flatMapLatest { result ->
                            when (result) {
                                is AppResult.Failed -> flowOf(result)
                                is AppResult.Success -> {
                                    val denial = result.value.denial
                                    if (denial != null) flowOf(AppResult.Failed(denial))
                                    else observeReplica(admitted.value, selection)
                                }
                            }
                        }
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: WorkforceAccess,
        selection: OvertimeSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeOverview>?>> =
        replicas.observeDirectory(access).flatMapLatest { result ->
            when (result) {
                is AppResult.Failed -> flowOf(result)
                is AppResult.Success -> {
                    val metadata = result.value
                    val checkpoint = metadata.checkpoint
                    if (checkpoint == null) observeLegacy(access, selection)
                    else
                        when (val owner = validateStoredOvertimeReplica(access, checkpoint)) {
                            is AppResult.Failed -> flowOf(owner)
                            is AppResult.Success -> {
                                val directory = requireNotNull(metadata.directory)
                                when (val chosen = selectStoredOvertime(directory, selection)) {
                                    is AppResult.Failed -> flowOf(chosen)
                                    is AppResult.Success -> {
                                        val selected = chosen.value
                                        val page =
                                            pageOvertimeEmployments(
                                                directory,
                                                selected.employmentAfter,
                                            )
                                        if (selected.employeeId == null)
                                            flowOf(
                                                AppResult.Success(
                                                    OvertimeSnapshot(
                                                        access,
                                                        checkpoint.generation,
                                                        OvertimeOverview(
                                                            page,
                                                            selected,
                                                            OvertimePage(emptyList()),
                                                        ),
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
                                                        if (
                                                            content.checkpoint?.generation !=
                                                                checkpoint.generation ||
                                                                content.checkpoint.revision !=
                                                                    checkpoint.revision
                                                        )
                                                            null
                                                        else
                                                            AppResult.Success(
                                                                OvertimeSnapshot(
                                                                    access,
                                                                    checkpoint.generation,
                                                                    OvertimeOverview(
                                                                        page,
                                                                        selected,
                                                                        content.requests,
                                                                    ),
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

    /** The last-view projection remains readable until the first complete replica publishes. */
    private fun observeLegacy(
        access: WorkforceAccess,
        selection: OvertimeSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeOverview>?>> =
        cache.observe(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val owner = validateStoredOvertime(access, result.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success ->
                            if (
                                selection == OvertimeSelection() ||
                                    result.value?.value?.selection == selection
                            )
                                result
                            else AppResult.Success(null)
                    }
            }
        }
}
