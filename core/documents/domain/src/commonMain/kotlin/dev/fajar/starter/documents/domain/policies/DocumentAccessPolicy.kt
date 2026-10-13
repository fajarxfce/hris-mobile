package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Instant

fun admitDocumentCatalog(
    workspaceId: String,
    workspace: Workspace?,
    session: Session?,
    build: AppBuild,
    now: Instant,
): AppResult<DocumentAccess> {
    if (session == null) return documentFailure("client.session_changed", FailureKind.Unauthorized)
    val ownership = validateLocalWorkspaceOwnership(workspaceId, workspace, session)
    if (ownership is AppResult.Failed) return ownership
    requireNotNull(workspace)
    val module = requireCompanyModule(workspace, CompanyModule.DOCUMENTS)
    if (module is AppResult.Failed) return module
    val policy =
        workspace.policy
            ?: return documentFailure("client.company_policy_unavailable", FailureKind.Unavailable)
    when (companyAvailability(policy, build, now)) {
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
    if ("documents.self.read" !in workspace.permissions)
        return documentFailure("access_denied", FailureKind.AccessDenied)
    var deadline = workspace.admission.expiresAt
    var authorizationExpiryCode = "client.workspace_expired"
    policy.policy.maintenance?.let { window ->
        val start = policy.requestedAt + (window.startsAt - policy.policy.serverTime)
        if (start > now && start < deadline) {
            deadline = start
            authorizationExpiryCode = "company_maintenance"
        }
    }
    return AppResult.Success(
        DocumentAccess(
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

fun sameDocumentScope(first: DocumentAccess, second: DocumentAccess): Boolean =
    first.sessionId == second.sessionId &&
        first.accountId == second.accountId &&
        first.audience == second.audience &&
        first.companyId == second.companyId &&
        first.permissions == second.permissions &&
        first.sessionExpiresAt == second.sessionExpiresAt

fun validateDocumentContinuation(
    expected: DocumentAccess,
    current: AppResult<DocumentAccess>,
): AppResult<DocumentAccess> =
    when (current) {
        is AppResult.Failed -> current
        is AppResult.Success ->
            if (sameDocumentScope(expected, current.value)) current
            else documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
    }

fun documentFailure(code: String, kind: FailureKind = FailureKind.Validation): AppResult.Failed =
    AppResult.Failed(Failure(kind, "Documents are unavailable.", code = code))
