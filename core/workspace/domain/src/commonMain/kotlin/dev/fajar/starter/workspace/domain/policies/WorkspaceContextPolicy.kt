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
    if (workspace == null) return WorkspaceContext(session.id, null, null)
    val admission = validateLocalWorkspaceOwnership(workspace.id, workspace, session)
    if (admission is AppResult.Failed) return WorkspaceContext(session.id, null, null)
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
