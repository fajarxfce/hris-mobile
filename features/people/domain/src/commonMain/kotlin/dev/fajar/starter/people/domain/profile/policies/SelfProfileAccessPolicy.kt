package dev.fajar.starter.people.domain.profile.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.people.domain.profile.entities.SelfProfileAccess
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Instant

fun admitSelfProfile(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<SelfProfileAccess> {
    val owned = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
    if (owned is AppResult.Failed) return owned
    requireNotNull(workspace)
    requireNotNull(session)
    val available = requireCompanyModule(workspace, CompanyModule.PEOPLE)
    if (available is AppResult.Failed) return available
    if (
        "people.self.read" !in workspace.permissions &&
            !("people.read" in workspace.permissions &&
                "people.profile.read" in workspace.permissions)
    )
        return selfProfileFailure("access_denied", FailureKind.AccessDenied)
    when (companyAvailability(workspace.policy, build, now)) {
        CompanyAvailability.Unknown ->
            return selfProfileFailure("client.company_policy_unavailable", FailureKind.Unavailable)
        CompanyAvailability.ClientMetadataRequired ->
            return selfProfileFailure("client_version_required", FailureKind.AccessDenied)
        is CompanyAvailability.UpdateRequired ->
            return selfProfileFailure("client_update_required", FailureKind.AccessDenied)
        is CompanyAvailability.Maintenance ->
            return selfProfileFailure("company_maintenance", FailureKind.Service)
        CompanyAvailability.Available -> Unit
    }
    return AppResult.Success(
        SelfProfileAccess(
            session.id,
            session.user.id,
            session.binding.audience,
            workspace.company.id,
            workspace.company.timezone,
            workspace.permissions.toSet(),
        )
    )
}

fun sameStoredSelfProfileScope(first: SelfProfileAccess, second: SelfProfileAccess) =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId

fun validateSelfProfileContinuation(
    expected: SelfProfileAccess,
    current: AppResult<SelfProfileAccess>,
): AppResult<SelfProfileAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (expected == current.value) current
            else selfProfileFailure("client.self_profile_scope_changed", FailureKind.AccessDenied)
    }

fun selfProfileFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "Your profile could not be loaded.", code = code))

fun selfProfileFailureClosesRead(failure: Failure): Boolean =
    failure.kind == FailureKind.AccessDenied ||
        failure.code in workspaceAccessLossCodes ||
        failure.code in
            setOf(
                "sync_scope_changed",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )
