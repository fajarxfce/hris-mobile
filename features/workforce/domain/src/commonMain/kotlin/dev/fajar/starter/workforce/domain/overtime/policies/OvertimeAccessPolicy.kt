package dev.fajar.starter.workforce.domain.overtime.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeSnapshot
import dev.fajar.starter.workforce.domain.policies.admitWorkforceAccess
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import dev.fajar.starter.workspace.domain.entities.Workspace
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

val OVERTIME_FRESHNESS_PERIOD = 1.hours

fun boundOvertimeAuthorization(
    access: WorkforceAccess,
    policy: CompanyPolicySnapshot,
    now: Instant,
): WorkforceAccess {
    val startsAt =
        policy.policy.maintenance?.let {
            policy.requestedAt + (it.startsAt - policy.policy.serverTime)
        }
    return if (startsAt != null && startsAt > now && startsAt < access.authorizationExpiresAt)
        access.copy(
            authorizationExpiresAt = startsAt,
            authorizationExpiryCode = "company_maintenance",
        )
    else access
}

fun admitOvertime(
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
        setOf("overtime.self.manage", "overtime.read", "overtime.manage", "workforce.read"),
    )

fun validateOvertimeContinuation(
    expected: WorkforceAccess,
    current: AppResult<WorkforceAccess>,
): AppResult<WorkforceAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (sameWorkforceScope(expected, current.value)) current
            else overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
    }

fun validateOvertimeActionSnapshot(
    access: WorkforceAccess,
    snapshot: OvertimeSnapshot<*>?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.authorizationExpiresAt <= now ->
            overtimeFailure(access.authorizationExpiryCode, FailureKind.Unavailable)
        snapshot == null -> AppResult.Success(Unit)
        !sameWorkforceScope(access, snapshot.access) ->
            overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.refreshAfter <= now ||
            snapshot.refreshAfter > snapshot.fetchedAt + OVERTIME_FRESHNESS_PERIOD ->
            overtimeFailure("client.overtime_cache_expired", FailureKind.Unavailable)
        else -> AppResult.Success(Unit)
    }

/** Stored facts remain readable after freshness expires; commands validate current authority. */
fun validateStoredOvertime(
    access: WorkforceAccess,
    snapshot: OvertimeSnapshot<*>?,
): AppResult<Unit> =
    if (snapshot != null && !sameStoredOvertimeScope(access, snapshot.access))
        overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
    else AppResult.Success(Unit)
