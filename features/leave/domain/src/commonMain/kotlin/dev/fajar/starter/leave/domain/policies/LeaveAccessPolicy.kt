package dev.fajar.starter.leave.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val LEAVE_FRESHNESS_PERIOD = 1.hours

fun admitLeave(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<LeaveAccess> {
    val ownership = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
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
    var authorizationExpiryCode = "client.workspace_expired"
    val policy = requireNotNull(workspace.policy)
    policy.policy.maintenance?.let { window ->
        val start = policy.requestedAt + (window.startsAt - policy.policy.serverTime)
        if (start > now && start < deadline) {
            deadline = start
            authorizationExpiryCode = "company_maintenance"
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
            authorizationExpiryCode,
        )
    )
}

fun sameLeaveScope(first: LeaveAccess, second: LeaveAccess): Boolean =
    sameLeaveSessionOwner(first, second) && first.sessionExpiresAt == second.sessionExpiresAt

/** Legacy ciphertext is session-bound, but credential renewal does not change its owner. */
fun sameLeaveSessionOwner(first: LeaveAccess, second: LeaveAccess): Boolean =
    first.sessionId == second.sessionId && sameStoredLeaveScope(first, second)

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

/** Stored facts remain readable after freshness expires; commands validate current authority. */
fun validateStoredLeave(access: LeaveAccess, snapshot: LeaveOverview?): AppResult<Unit> =
    if (snapshot != null && !sameLeaveSessionOwner(access, snapshot.access))
        leaveFailure("client.leave_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)
