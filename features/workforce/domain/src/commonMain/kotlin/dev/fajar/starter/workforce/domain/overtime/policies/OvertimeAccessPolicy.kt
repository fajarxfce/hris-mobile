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

val OVERTIME_CACHE_PERIOD = 1.hours

fun boundOvertimeReadAccess(
    access: WorkforceAccess,
    policy: CompanyPolicySnapshot,
    now: Instant,
): WorkforceAccess {
    val startsAt =
        policy.policy.maintenance?.let {
            policy.requestedAt + (it.startsAt - policy.policy.serverTime)
        }
    return if (startsAt != null && startsAt > now && startsAt < access.readUntil)
        access.copy(readUntil = startsAt, expiryCode = "company_maintenance")
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

fun validateOvertimeCache(
    access: WorkforceAccess,
    snapshot: OvertimeSnapshot<*>?,
    now: Instant,
): AppResult<Unit> =
    when {
        access.readUntil <= now -> overtimeFailure(access.expiryCode, FailureKind.AccessDenied)
        snapshot == null -> AppResult.Success(Unit)
        !sameWorkforceScope(access, snapshot.access) ->
            overtimeFailure("client.overtime_scope_changed", FailureKind.AccessDenied)
        snapshot.fetchedAt > now ||
            snapshot.expiresAt <= now ||
            snapshot.expiresAt > snapshot.fetchedAt + OVERTIME_CACHE_PERIOD ->
            overtimeFailure("client.overtime_cache_expired", FailureKind.AccessDenied)
        else -> AppResult.Success(Unit)
    }
