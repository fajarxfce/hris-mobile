package dev.fajar.starter.payroll.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val PAYSLIP_CACHE_PERIOD = 1.hours

fun admitPayroll(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<PayrollAccess> {
    val ownership = validateWorkspaceOwnership(workspaceId, workspace, session, now)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    val module = requireCompanyModule(workspace, CompanyModule.PAYROLL)
    if (module is AppResult.Failed) return module
    when (companyAvailability(workspace.policy, build, now)) {
        CompanyAvailability.Unknown ->
            return companyPolicyFailure(
                "client.company_policy_unavailable",
                FailureKind.Unavailable,
            )
        CompanyAvailability.ClientMetadataRequired ->
            return companyPolicyFailure("client_version_required")
        is CompanyAvailability.UpdateRequired ->
            return companyPolicyFailure("client_update_required")
        is CompanyAvailability.Maintenance ->
            return companyPolicyFailure("company_maintenance", FailureKind.Service)
        CompanyAvailability.Available -> Unit
    }
    if (workspace.permissions.none { it in setOf("payroll.self.read", "payroll.read") })
        return payslipFailure("access_denied", FailureKind.AccessDenied)
    return AppResult.Success(
        boundPayrollReadAccess(
            PayrollAccess(
                session.id,
                session.user.id,
                session.binding.audience,
                workspace.company.id,
                workspace.company.timezone,
                workspace.permissions.toSet(),
                Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
                workspace.admission.expiresAt,
                "client.workspace_expired",
            ),
            requireNotNull(workspace.policy),
            now,
        )
    )
}

fun boundPayrollReadAccess(
    access: PayrollAccess,
    policy: CompanyPolicySnapshot,
    now: Instant,
): PayrollAccess {
    val start =
        policy.policy.maintenance?.let {
            policy.requestedAt + (it.startsAt - policy.policy.serverTime)
        }
    return if (start != null && start > now && start < access.readUntil)
        access.copy(readUntil = start, expiryCode = "company_maintenance")
    else access
}

fun samePayrollScope(first: PayrollAccess, second: PayrollAccess): Boolean =
    first.sessionId == second.sessionId &&
        first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.timezone == second.timezone &&
        first.permissions == second.permissions &&
        first.sessionExpiresAt == second.sessionExpiresAt

fun validatePayrollContinuation(
    expected: PayrollAccess,
    current: AppResult<PayrollAccess>,
): AppResult<PayrollAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (samePayrollScope(expected, current.value)) current
            else payslipFailure("client.payslip_scope_changed", FailureKind.AccessDenied)
    }

fun validatePayslipCache(
    access: PayrollAccess,
    snapshot: PayslipSnapshot<*>?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.readUntil <= now -> payslipFailure(access.expiryCode, FailureKind.AccessDenied)
        snapshot == null -> AppResult.Success(Unit)
        !samePayrollScope(access, snapshot.access) ->
            payslipFailure("client.payslip_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.expiresAt <= now ||
            snapshot.expiresAt > snapshot.fetchedAt + PAYSLIP_CACHE_PERIOD ->
            payslipFailure("client.payslip_cache_expired", FailureKind.AccessDenied)
        else -> AppResult.Success(Unit)
    }
