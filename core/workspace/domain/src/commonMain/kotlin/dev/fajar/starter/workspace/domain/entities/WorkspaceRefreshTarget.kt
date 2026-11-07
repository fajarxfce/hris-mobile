package dev.fajar.starter.workspace.domain.entities

import dev.fajar.starter.identity.domain.entities.Session

/** Local work coordinates captured before renewing current server authority. */
data class WorkspaceRefreshTarget(
    val sessionId: String,
    val workspaceId: String,
    val audience: String,
    val accountId: String,
    val companyId: String,
) {
    fun matches(session: Session?, workspace: Workspace?) =
        session != null &&
            workspace != null &&
            session.id == sessionId &&
            session.binding.audience == audience &&
            session.user.id == accountId &&
            workspace.id == workspaceId &&
            workspace.sessionId == sessionId &&
            workspace.audience == audience &&
            workspace.company.id == companyId

    override fun toString() = "WorkspaceRefreshTarget([redacted])"
}
