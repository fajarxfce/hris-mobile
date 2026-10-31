package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.policies.*
import kotlin.time.Instant

/** Live operation admission; owned local chat facts have an independent lifetime. */
fun collaborationRequestOwner(
    workspaceId: String,
    session: Session?,
    workspace: Workspace?,
    server: IdentityServer,
    build: AppBuild,
    now: Instant,
): AppResult<CollaborationOwner> {
    val owned = validateWorkspaceOwnership(workspaceId, workspace, session, now)
    if (owned is AppResult.Failed) return owned
    requireNotNull(session)
    requireNotNull(workspace)
    if (session.binding.audience != server.audience)
        return workspaceFailure("client.session_changed", FailureKind.Unauthorized)
    if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
        return workspaceFailure("client.collaboration_native_required", FailureKind.Unavailable)
    if ("collaboration.chat" !in workspace.permissions) return workspaceFailure("permission_denied")
    val policy =
        workspace.policy
            ?: return companyPolicyFailure(
                "client.company_policy_unavailable",
                FailureKind.Unavailable,
            )
    val available = admitCompanyOperation(policy, build, CompanyModule.COLLABORATION, now)
    if (available is AppResult.Failed) return available
    return AppResult.Success(
        CollaborationOwner(
            session.id,
            server.audience,
            session.user.id,
            workspace.company.id,
            session.binding.serverId,
        )
    )
}
