package dev.fajar.starter.workspace.domain.policies

import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.workspace.domain.entities.*
import kotlin.time.Instant

/** Local selection is independent of network credentials and authorization freshness. */
fun workspaceContext(
    session: Session,
    workspace: Workspace?,
    build: AppBuild,
    now: Instant,
): WorkspaceContext {
    val owner =
        WorkspaceSelectionOwner(
            session.id,
            session.binding.audience,
            session.user.id,
            session.binding.serverId,
        )
    if (workspace == null) return WorkspaceContext(session.id, null, null, owner)
    val admission = validateLocalWorkspaceOwnership(workspace.id, workspace, session)
    if (admission is AppResult.Failed) return WorkspaceContext(session.id, null, null, owner)
    return WorkspaceContext(
        session.id,
        workspace,
        CompanyPolicyStatus(
            workspace.id,
            companyAvailability(workspace.policy, build, now),
            !companyPolicyIsFresh(workspace.policy, now),
        ),
        owner,
    )
}
