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

class ObservePayslips(
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
        selection: PayslipSelection = PayslipSelection(),
    ): Flow<AppResult<PayslipSnapshot<PayslipOverview>?>> {
        val valid = validatePayslipSelection(selection)
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
                    is AppResult.Success -> observeReplica(admitted.value, selection)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeReplica(
        access: PayrollAccess,
        selection: PayslipSelection,
    ): Flow<AppResult<PayslipSnapshot<PayslipOverview>?>> {
        val selected =
            selection.copy(
                year =
                    selection.year ?: clock.now().toLocalDateTime(TimeZone.of(access.timezone)).year
            )
        return replicas.observeAdmission(access).flatMapLatest { admitted ->
            when (admitted) {
                is AppResult.Failed -> flowOf(admitted)
                is AppResult.Success -> {
                    val denial = admitted.value.denial
                    if (denial != null) flowOf(AppResult.Failed(denial))
                    else
                        replicas.observe(access, selected).flatMapLatest { result ->
                            when (result) {
                                is AppResult.Failed -> flowOf(result)
                                is AppResult.Success -> {
                                    val checkpoint = result.value.checkpoint
                                    if (checkpoint == null) observeLegacy(access, selection)
                                    else
                                        when (
                                            val owner =
                                                validateStoredPayrollReplica(access, checkpoint)
                                        ) {
                                            is AppResult.Failed -> flowOf(owner)
                                            is AppResult.Success ->
                                                flowOf(
                                                    AppResult.Success(
                                                        PayslipSnapshot(
                                                            access,
                                                            checkpoint.generation,
                                                            PayslipOverview(
                                                                selected,
                                                                result.value.page,
                                                            ),
                                                            requireNotNull(checkpoint.refreshedAt),
                                                            checkpoint.refreshAfter,
                                                        )
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

    /** Keep only the matching last-view snapshot until the first full replica publishes. */
    private fun observeLegacy(
        access: PayrollAccess,
        selection: PayslipSelection,
    ): Flow<AppResult<PayslipSnapshot<PayslipOverview>?>> =
        cache.observe(access).map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success ->
                    when (val owner = validateStoredPayslip(access, result.value)) {
                        is AppResult.Failed -> owner
                        is AppResult.Success ->
                            AppResult.Success(
                                result.value?.takeIf {
                                    (selection.year == null ||
                                        selection.year == it.value.selection.year) &&
                                        selection.after == it.value.selection.after
                                }
                            )
                    }
            }
        }
}
