package dev.fajar.starter.workforce.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.*
import dev.fajar.starter.workforce.domain.repositories.WorkCalendarCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveWorkCalendar(
    private val cache: WorkCalendarCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(workspaceId: String): Flow<AppResult<WorkCalendarSnapshot?>> =
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
                                        admitWorkCalendar(
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
                        cache.observe(admitted.value).flatMapLatest { result ->
                            flow<AppResult<WorkCalendarSnapshot?>> {
                                if (result is AppResult.Failed) {
                                    emit(result)
                                    return@flow
                                }
                                val snapshot = (result as AppResult.Success).value
                                val validation =
                                    validateCalendarCache(admitted.value, snapshot, clock.now())
                                if (validation is AppResult.Failed) {
                                    emit(validation)
                                    return@flow
                                }
                                emit(result)
                                val deadline =
                                    minOf(
                                        admitted.value.readUntil,
                                        snapshot?.expiresAt ?: admitted.value.readUntil,
                                    )
                                delay((deadline - clock.now()).inWholeMilliseconds.coerceAtLeast(0))
                                emit(
                                    calendarFailure(
                                        if (admitted.value.readUntil <= deadline)
                                            admitted.value.expiryCode
                                        else "client.calendar_cache_expired",
                                        FailureKind.AccessDenied,
                                    )
                                )
                            }
                        }
                }
            }
}
