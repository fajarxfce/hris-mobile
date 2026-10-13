package dev.fajar.starter.workforce.domain.attendance.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.attendance.policies.*
import dev.fajar.starter.workforce.domain.attendance.repositories.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObserveAttendance(
    private val cache: AttendanceCacheRepository,
    private val commands: AttendanceCommandRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        window: AttendanceWindow = AttendanceWindow.RECENT,
    ): Flow<AppResult<AttendanceView>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(calendarFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitWorkforce(
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
                        combine(
                            cache.observe(admitted.value, window),
                            commands.observe(admitted.value),
                        ) { result, records ->
                            when {
                                result is AppResult.Failed -> result
                                records is AppResult.Failed -> records
                                else -> {
                                    val snapshot = (result as AppResult.Success).value
                                    when (
                                        val ownership =
                                            validateStoredAttendance(admitted.value, snapshot)
                                    ) {
                                        is AppResult.Failed -> ownership
                                        is AppResult.Success ->
                                            AppResult.Success(
                                                AttendanceView(
                                                    snapshot,
                                                    (records as AppResult.Success).value,
                                                )
                                            )
                                    }
                                }
                            }
                        }
                }
            }
}
