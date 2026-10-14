package dev.fajar.starter.payroll.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.policies.*
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.payroll.domain.repositories.PayslipCacheRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class ObservePayslip(
    private val replicas: PayslipReplicaRepository,
    private val cache: PayslipCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        id: String,
    ): Flow<AppResult<PayslipSnapshot<PayslipView>?>> {
        val valid = validatePayslipId(id)
        if (valid is AppResult.Failed) return flowOf(valid)
        return sessions
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
                    is AppResult.Success -> observeReplica(admitted.value, id)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: PayrollAccess,
        id: String,
    ): Flow<AppResult<PayslipSnapshot<PayslipView>?>> =
        replicas.observeAdmission(access).flatMapLatest { admitted ->
            when (admitted) {
                is AppResult.Failed -> flowOf(admitted)
                is AppResult.Success -> {
                    val denial = admitted.value.denial
                    if (denial != null) flowOf(AppResult.Failed(denial))
                    else
                        replicas.observeDetails(access, id).flatMapLatest { result ->
                            when (result) {
                                is AppResult.Failed -> flowOf(result)
                                is AppResult.Success -> {
                                    val checkpoint = result.value.checkpoint
                                    if (checkpoint == null) observeLegacy(access, id)
                                    else
                                        when (
                                            val owner =
                                                validateStoredPayrollReplica(access, checkpoint)
                                        ) {
                                            is AppResult.Failed -> flowOf(owner)
                                            is AppResult.Success ->
                                                flowOf(
                                                    AppResult.Success(
                                                        result.value.view?.let {
                                                            PayslipSnapshot(
                                                                access,
                                                                checkpoint.generation,
                                                                it,
                                                                requireNotNull(
                                                                    checkpoint.refreshedAt
                                                                ),
                                                                checkpoint.refreshAfter,
                                                            )
                                                        }
                                                    )
                                                )
                                        }
                                }
                            }
                        }
                }
            }
        }

    private fun observeLegacy(
        access: PayrollAccess,
        id: String,
    ): Flow<AppResult<PayslipSnapshot<PayslipView>?>> =
        cache.observeDetails(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val owner = validateStoredPayslip(access, result.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success ->
                            AppResult.Success(
                                result.value?.takeIf { it.value.payslip.summary.id == id }
                            )
                    }
            }
        }
}
