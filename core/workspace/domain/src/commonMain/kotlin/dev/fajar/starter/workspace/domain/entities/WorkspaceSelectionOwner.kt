package dev.fajar.starter.workspace.domain.entities

import dev.fajar.starter.identity.domain.entities.Session

/** Identity captured before an explicit action requests a different company. */
data class WorkspaceSelectionOwner(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val familyId: String,
) {
    fun matches(session: Session?) =
        session != null &&
            session.id == sessionId &&
            session.binding.audience == audience &&
            session.user.id == accountId &&
            session.binding.serverId == familyId

    override fun toString() = "WorkspaceSelectionOwner([redacted])"
}
