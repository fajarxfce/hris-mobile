package dev.fajar.starter.app.navigation

import dev.fajar.starter.workspace.domain.entities.Workspace

/** A process-local intent acquires one session/company owner and never moves to another. */
data class PendingAppLink(
    val target: AppLink,
    val sessionId: String? = null,
    val workspaceId: String? = null,
) {
    fun inContext(
        sessionId: String?,
        workspace: Workspace?,
        owner: dev.fajar.starter.workspace.domain.entities.WorkspaceSelectionOwner? = null,
    ): PendingAppLink? {
        if (sessionId == null) return takeIf { this.sessionId == null }
        if (this.sessionId != null && this.sessionId != sessionId) return null
        if (workspace != null && workspace.sessionId != sessionId) return null
        if (workspaceId != null && workspaceId != workspace?.id) return null
        if (
            workspace != null &&
                target is AppLink.InboxItem &&
                (target.accountId != workspace.admission.accountId ||
                    target.companyId != workspace.company.id)
        )
            return null
        if (target is AppLink.ChatThread) {
            if (owner != target.owner) return null
            if (
                workspace != null &&
                    (workspace.audience != owner.audience ||
                        workspace.admission.accountId != owner.accountId ||
                        workspace.admission.familyId != owner.familyId ||
                        workspace.company.id != target.companyId)
            )
                return null
        }
        return copy(sessionId = sessionId, workspaceId = workspace?.id)
    }

    override fun toString() = "PendingAppLink([redacted])"
}
