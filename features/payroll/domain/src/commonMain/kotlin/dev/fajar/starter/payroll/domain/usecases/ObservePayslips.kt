package dev.fajar.starter.payroll.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.repositories.PayslipCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class ObservePayslips(
    private val cache: PayslipCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(workspaceId: String): Flow<AppResult<PayslipSnapshot<PayslipOverview>?>> =
        sessions
            .observe()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val session = result.value
                        if (session == null)
                            flowOf(payslipFailure("session_revoked", FailureKind.Unauthorized))
                        else
                            workspaces.observe(session.id).map { current ->
                                when (current) {
                                    is AppResult.Failed -> current
                                    is AppResult.Success ->
                                        admitPayroll(
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
                        cache.observe(admitted.value).map { result ->
                            when (result) {
                                is AppResult.Failed -> result
                                is AppResult.Success ->
                                    when (
                                        val ownership =
                                            validateStoredPayslip(admitted.value, result.value)
                                    ) {
                                        is AppResult.Failed -> ownership
                                        is AppResult.Success -> result
                                    }
                            }
                        }
                }
            }
}
