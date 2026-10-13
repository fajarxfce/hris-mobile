package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Instant

/** Retains only an owned renewal identifier when private workspace access has expired. */
fun workspaceContext(
    session: Session,
    workspace: Workspace?,
    build: AppBuild,
    now: Instant,
): WorkspaceContext {
    if (workspace == null) return WorkspaceContext(session.id, null, null)
    val admission = validateWorkspaceOwnership(workspace.id, workspace, session, now)
    if (admission is AppResult.Failed)
        return WorkspaceContext(
            session.id,
            null,
            null,
            expiredWorkspaceId =
                workspace.id.takeIf { admission.failure.code == "client.workspace_expired" },
        )
    return WorkspaceContext(
        session.id,
        workspace,
        CompanyPolicyStatus(
            workspace.id,
            companyAvailability(workspace.policy, build, now),
            !companyPolicyIsFresh(workspace.policy, now),
        ),
    )
}
