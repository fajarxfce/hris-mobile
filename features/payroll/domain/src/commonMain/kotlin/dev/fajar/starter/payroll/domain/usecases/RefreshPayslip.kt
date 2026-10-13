@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.payroll.domain.usecases

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.*
import dev.fajar.starter.payroll.domain.repositories.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.admitCompanyOperation
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

class RefreshPayslip(
    private val remote: PayslipRepository,
    private val cache: PayslipCacheRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock,
    private val build: AppBuild,
) {
    suspend operator fun invoke(workspaceId: String, id: String): AppResult<Unit> {
        val valid = validatePayslipId(id)
        if (valid is AppResult.Failed) return valid
        val access =
            when (val result = withTimeoutOrNull(5.seconds) { currentAccess(workspaceId) }) {
                null -> return payslipFailure("client.payslip_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val generation =
            when (val result = withTimeoutOrNull(5.seconds) { cache.detailsGeneration(access) }) {
                null -> return payslipFailure("client.payslip_timeout", FailureKind.Timeout)
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val result =
            withTimeoutOrNull(30.seconds) { acquire(workspaceId, id, access, generation) }
                ?: payslipFailure("client.payslip_timeout", FailureKind.Timeout)
        currentCoroutineContext().ensureActive()
        if (result is AppResult.Failed && payslipFailureInvalidatesCache(result.failure)) {
            withTimeoutOrNull(2.seconds) { cache.replaceDetails(access, generation, null) }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private suspend fun acquire(
        workspaceId: String,
        id: String,
        access: PayrollAccess,
        generation: String?,
    ): AppResult<Unit> {
        val before = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (before is AppResult.Failed) return before
        val requestedAt = clock.now()
        val policy =
            when (val response = workspaces.clientPolicy(access.sessionId, access.companyId)) {
                is AppResult.Failed -> return response
                is AppResult.Success -> CompanyPolicySnapshot(response.value, requestedAt)
            }
        currentCoroutineContext().ensureActive()
        val ownership = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (ownership is AppResult.Failed) return ownership
        val availability = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (availability is AppResult.Failed) return availability
        val payslip =
            when (val response = remote.details(access, id)) {
                is AppResult.Failed -> return response
                is AppResult.Success -> response.value
            }
        currentCoroutineContext().ensureActive()
        val continuation = validatePayrollContinuation(access, currentAccess(workspaceId))
        if (continuation is AppResult.Failed) return continuation
        val stillAvailable =
            admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (stillAvailable is AppResult.Failed) return stillAvailable
        val payments =
            when (val response = remote.payments(access, id)) {
                is AppResult.Failed -> return response
                is AppResult.Success -> response.value
            }
        currentCoroutineContext().ensureActive()
        val current =
            when (val response = validatePayrollContinuation(access, currentAccess(workspaceId))) {
                is AppResult.Failed -> return response
                is AppResult.Success -> response.value
            }
        val admitted = admitCompanyOperation(policy, build, CompanyModule.PAYROLL, clock.now())
        if (admitted is AppResult.Failed) return admitted
        if (payslip.summary.id != id || payments.payslipId != id)
            return payslipFailure("client.invalid_response", FailureKind.Unexpected)
        val now = clock.now()
        val lease = boundPayrollReadAccess(current, policy, now)
        val snapshot =
            PayslipSnapshot(
                lease,
                Uuid.random().toString(),
                PayslipView(payslip, payments),
                now,
                minOf(now + PAYSLIP_CACHE_PERIOD, lease.readUntil),
            )
        when (val response = cache.replaceDetails(access, generation, snapshot)) {
            is AppResult.Failed -> return response
            is AppResult.Success ->
                if (!response.value)
                    return payslipFailure("client.payslip_changed", FailureKind.Conflict)
        }
        currentCoroutineContext().ensureActive()
        return when (
            val continuation = validatePayrollContinuation(access, currentAccess(workspaceId))
        ) {
            is AppResult.Failed -> continuation
            is AppResult.Success -> AppResult.Success(Unit)
        }
    }

    private suspend fun currentAccess(workspaceId: String): AppResult<PayrollAccess> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return payslipFailure("session_revoked", FailureKind.Unauthorized)
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val latest =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return admitPayroll(workspaceId, workspace, latest, build, clock.now())
    }
}
