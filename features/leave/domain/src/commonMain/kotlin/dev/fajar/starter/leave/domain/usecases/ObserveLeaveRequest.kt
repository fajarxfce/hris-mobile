package dev.fajar.starter.leave.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import dev.fajar.starter.leave.domain.repositories.LeaveRequestCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveLeaveRequest(
    private val cache: LeaveRequestCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        requestId: String,
    ): Flow<AppResult<LeaveRequestSnapshot?>> =
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
                        cache.observeRequest(admitted.value).flatMapLatest { result ->
                            flow<AppResult<LeaveRequestSnapshot?>> {
                                if (result is AppResult.Failed) {
                                    emit(result)
                                    return@flow
                                }
                                val snapshot =
                                    (result as AppResult.Success).value?.takeIf {
                                        it.request.id == requestId
                                    }
                                val validation =
                                    validateLeaveRequestCache(admitted.value, snapshot, clock.now())
                                if (validation is AppResult.Failed) {
                                    emit(validation)
                                    return@flow
                                }
                                emit(AppResult.Success(snapshot))
                                val deadline =
                                    minOf(
                                        admitted.value.readUntil,
                                        snapshot?.expiresAt ?: admitted.value.readUntil,
                                    )
                                delay((deadline - clock.now()).inWholeMilliseconds.coerceAtLeast(0))
                                emit(
                                    leaveFailure(
                                        if (admitted.value.readUntil <= deadline)
                                            admitted.value.expiryCode
                                        else "client.leave_cache_expired",
                                        FailureKind.AccessDenied,
                                    )
                                )
                            }
                        }
                }
            }
}
