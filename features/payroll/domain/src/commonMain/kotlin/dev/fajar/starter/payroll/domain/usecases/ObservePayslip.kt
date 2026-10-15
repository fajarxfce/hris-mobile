package dev.fajar.starter.payroll.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.replica.policies.*
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.payroll.domain.repositories.PayslipCacheRepository
import dev.fajar.starter.payroll.domain.repositories.PayslipRevisionRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

class ObservePayslip(
    private val replicas: PayslipReplicaRepository,
    private val cache: PayslipCacheRepository,
    private val revisions: PayslipRevisionRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        workspaceId: String,
        id: String,
        revision: Long? = null,
    ): Flow<AppResult<PayslipRevisionRead?>> {
        val valid = validatePayslipId(id)
        if (valid is AppResult.Failed) return flowOf(valid)
        if (revision != null && revision < 0)
            return flowOf(payslipFailure("client.payslip_revision_invalid"))
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
                    is AppResult.Success -> observeSelection(admitted.value, id, revision)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSelection(
        access: PayrollAccess,
        id: String,
        revision: Long?,
    ): Flow<AppResult<PayslipRevisionRead?>> =
        observeReplica(access, id).flatMapLatest { current ->
            when (current) {
                is AppResult.Failed -> flowOf(current)
                is AppResult.Success -> {
                    val snapshot = current.value
                    if (snapshot == null) flowOf(AppResult.Success(null))
                    else if (revision == null || snapshot.value.payslip.summary.version == revision)
                        flowOf(
                            AppResult.Success(
                                PayslipRevisionRead(
                                    snapshot.value.payslip.summary.version,
                                    snapshot,
                                )
                            )
                        )
                    else if (revision > snapshot.value.payslip.summary.version)
                        flowOf(payslipFailure("client.payslip_revision_invalid"))
                    else
                        revisions.observeRevision(access, id, revision).map { stored ->
                            when (stored) {
                                is AppResult.Failed -> stored
                                is AppResult.Success -> {
                                    val slip = stored.value
                                    val head = snapshot.value.payslip
                                    if (slip == null)
                                        AppResult.Success(
                                            PayslipRevisionRead(head.summary.version, null)
                                        )
                                    else
                                        when (
                                            val valid =
                                                validatePayslipRevision(head, revision, slip)
                                        ) {
                                            is AppResult.Failed -> valid
                                            is AppResult.Success ->
                                                AppResult.Success(
                                                    PayslipRevisionRead(
                                                        head.summary.version,
                                                        snapshot.copy(
                                                            value =
                                                                PayslipView(
                                                                    slip,
                                                                    snapshot.value.payments,
                                                                )
                                                        ),
                                                    )
                                                )
                                        }
                                }
                            }
                        }
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
