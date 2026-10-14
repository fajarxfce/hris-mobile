package dev.fajar.starter.people.domain.replica.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.people.domain.policies.requireCompanyDirectoryAccess
import dev.fajar.starter.people.domain.replica.entities.DirectoryAccess
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Instant

fun admitDirectory(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<DirectoryAccess> {
    val owned = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
    if (owned is AppResult.Failed) return owned
    requireNotNull(workspace)
    requireNotNull(session)
    val permitted = requireCompanyDirectoryAccess(workspace)
    if (permitted is AppResult.Failed) return permitted
    when (companyAvailability(workspace.policy, build, now)) {
        CompanyAvailability.Unknown ->
            return directoryFailure("client.company_policy_unavailable", FailureKind.Unavailable)
        CompanyAvailability.ClientMetadataRequired ->
            return directoryFailure("client_version_required", FailureKind.AccessDenied)
        is CompanyAvailability.UpdateRequired ->
            return directoryFailure("client_update_required", FailureKind.AccessDenied)
        is CompanyAvailability.Maintenance ->
            return directoryFailure("company_maintenance", FailureKind.Service)
        CompanyAvailability.Available -> Unit
    }
    return AppResult.Success(
        DirectoryAccess(
            session.id,
            session.user.id,
            session.binding.audience,
            workspace.company.id,
            workspace.company.timezone,
            workspace.permissions.toSet(),
        )
    )
}

fun sameStoredDirectoryScope(first: DirectoryAccess, second: DirectoryAccess) =
    first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId

fun validateDirectoryContinuation(
    expected: DirectoryAccess,
    current: AppResult<DirectoryAccess>,
): AppResult<DirectoryAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (expected == current.value) current
            else directoryFailure("client.directory_scope_changed", FailureKind.AccessDenied)
    }

fun directoryFailure(code: String, kind: FailureKind = FailureKind.Validation) =
    AppResult.Failed(Failure(kind, "The employee directory could not be loaded.", code = code))

/** A missing individual public record means withdrawal, not loss of directory access. */
fun directoryFailureClosesRead(failure: Failure): Boolean =
    failure.kind == FailureKind.AccessDenied ||
        failure.code in workspaceAccessLossCodes ||
        failure.code in
            setOf(
                "sync_scope_changed",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )
