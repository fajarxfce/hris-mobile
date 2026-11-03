package dev.fajar.starter.workspace.domain.entities

/** Null workspaceId explicitly means no selection when the action was received. */
data class WorkspaceSelectionOrigin(val owner: WorkspaceSelectionOwner, val workspaceId: String?) {
    override fun toString() = "WorkspaceSelectionOrigin([redacted])"
}
