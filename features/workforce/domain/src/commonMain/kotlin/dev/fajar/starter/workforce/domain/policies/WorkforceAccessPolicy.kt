package dev.fajar.starter.workforce.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val WORK_CALENDAR_CACHE_PERIOD = 1.hours

fun admitWorkforce(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<WorkforceAccess> =
    admitWorkforceAccess(
        workspaceId,
        workspace,
        session,
        build,
        now,
        setOf("attendance.self.record", "workforce.read"),
    )

internal fun admitWorkforceAccess(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
    permissions: Set<String>,
): AppResult<WorkforceAccess> {
    val ownership = validateWorkspaceOwnership(workspaceId, workspace, session, now)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    requireNotNull(session)
    val module = requireCompanyModule(workspace, CompanyModule.WORKFORCE)
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
    if (workspace.permissions.none { it in permissions })
        return calendarFailure("access_denied", FailureKind.AccessDenied)
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
        WorkforceAccess(
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

fun sameWorkforceScope(first: WorkforceAccess, second: WorkforceAccess): Boolean =
    first.sessionId == second.sessionId &&
        first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions &&
        first.sessionExpiresAt == second.sessionExpiresAt

fun validateWorkforceContinuation(
    expected: WorkforceAccess,
    current: AppResult<WorkforceAccess>,
): AppResult<WorkforceAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (sameWorkforceScope(expected, current.value)) current
            else calendarFailure("client.calendar_scope_changed", FailureKind.AccessDenied)
    }

fun validateCalendarCache(
    access: WorkforceAccess,
    snapshot: WorkCalendarSnapshot?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.readUntil <= now -> calendarFailure(access.expiryCode, FailureKind.AccessDenied)
        snapshot == null -> AppResult.Success(Unit)
        !sameWorkforceScope(access, snapshot.access) ->
            calendarFailure("client.calendar_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.expiresAt <= now ||
            snapshot.expiresAt > snapshot.fetchedAt + WORK_CALENDAR_CACHE_PERIOD ->
            calendarFailure("client.calendar_cache_expired", FailureKind.AccessDenied)
        else -> AppResult.Success(Unit)
    }
