package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Instant

fun collaborationLocalAccess(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    audience: String,
    build: AppBuild,
    now: Instant,
): AppResult<CollaborationLocalAccess> {
    val owned = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
    if (owned is AppResult.Failed) return owned
    requireNotNull(workspace)
    requireNotNull(session)
    if (session.binding.audience != audience)
        return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
    if ("collaboration.chat" !in workspace.permissions) return workspaceFailure("permission_denied")
    val module = requireCompanyModule(workspace, CompanyModule.COLLABORATION)
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
    return AppResult.Success(
        CollaborationLocalAccess(session.id, audience, session.user.id, workspace.company.id)
    )
}

fun CollaborationOwner.localListAccess() =
    CollaborationLocalAccess(sessionId, audience, accountId, companyId)

/**
 * Only company-summary acquisition uses this policy; a rejected recipient is not company denial.
 */
fun collaborationListResponseClosesRead(failure: Failure) =
    failure.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied) ||
        failure.code in workspaceAccessLossCodes ||
        failure.code in
            setOf(
                "company_module_disabled",
                "company_maintenance",
                "client_update_required",
                "client_version_required",
            )

fun collaborationListConflict() =
    AppResult.Failed(
        Failure(
            FailureKind.Conflict,
            "Refresh conversations again.",
            code = "client.collaboration_list_changed",
        )
    )
