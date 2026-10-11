package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val LEAVE_CACHE_PERIOD = 1.hours

fun admitLeave(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<LeaveAccess> {
    val ownership = validateWorkspaceOwnership(workspaceId, workspace, session, now)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    val module = requireCompanyModule(workspace, CompanyModule.LEAVE)
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
    if (workspace.permissions.none { it == "leave.self.manage" || it == "leave.read" })
        return leaveFailure("access_denied", FailureKind.AccessDenied)
    var deadline = workspace.admission.expiresAt
    var expiryCode = "client.workspace_expired"
    val policy = requireNotNull(workspace.policy)
    policy.policy.maintenance?.let { window ->
        val start = policy.requestedAt + (window.startsAt - policy.policy.serverTime)
        if (start > now && start < deadline) {
            deadline = start
            expiryCode = "company_maintenance"
        }
    }
    return AppResult.Success(
        LeaveAccess(
            session.id,
            session.user.id,
            session.binding.audience,
            workspace.company.id,
            workspace.permissions.toSet(),
            Instant.fromEpochMilliseconds(session.binding.expiresAtEpochMillis),
            deadline,
            expiryCode,
        )
    )
}

fun sameLeaveScope(first: LeaveAccess, second: LeaveAccess): Boolean =
    first.sessionId == second.sessionId &&
        first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions &&
        first.sessionExpiresAt == second.sessionExpiresAt

fun validateLeaveContinuation(
    expected: LeaveAccess,
    current: AppResult<LeaveAccess>,
): AppResult<LeaveAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (sameLeaveScope(expected, current.value)) current
            else leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
    }

fun validateLeaveCache(
    access: LeaveAccess,
    snapshot: LeaveOverview?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.readUntil <= now -> leaveFailure(access.expiryCode, FailureKind.AccessDenied)
        snapshot == null -> AppResult.Success(Unit)
        !sameLeaveScope(access, snapshot.access) ->
            leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.expiresAt <= now ||
            snapshot.expiresAt > snapshot.fetchedAt + LEAVE_CACHE_PERIOD ->
            leaveFailure("client.leave_cache_expired", FailureKind.AccessDenied)
        else -> AppResult.Success(Unit)
    }
