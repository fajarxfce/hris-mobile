package dev.fajar.starter.workforce.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val WORK_CALENDAR_FRESHNESS_PERIOD = 1.hours

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
    val ownership = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
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
    return AppResult.Success(
        boundWorkforceAuthorization(
            WorkforceAccess(
                session.id,
                session.user.id,
                session.binding.audience,
                workspace.company.id,
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

/** Scheduled maintenance limits action preparation, without expiring already stored facts. */
fun boundWorkforceAuthorization(
    access: WorkforceAccess,
    policy: CompanyPolicySnapshot,
    now: Instant,
): WorkforceAccess {
    val start =
        policy.policy.maintenance?.let {
            policy.requestedAt + (it.startsAt - policy.policy.serverTime)
        }
    return if (start != null && start > now && start < access.authorizationExpiresAt)
        access.copy(authorizationExpiresAt = start, authorizationExpiryCode = "company_maintenance")
    else access
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

/** Stored facts remain readable after freshness expires; commands validate current authority. */
fun validateStoredCalendar(
    access: WorkforceAccess,
    snapshot: WorkCalendarSnapshot?,
): AppResult<Unit> =
    if (snapshot != null && !sameWorkforceScope(access, snapshot.access))
        calendarFailure("client.calendar_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)
