package dev.fajar.starter.collaboration.domain.entities

/** Immutable demand/completion identity; cursor revisions cannot create another request. */
data class CollaborationSpaceInboxRepairSignals(
    val latestDemand: String?,
    val completedDemand: String?,
) {
    override fun toString() = "CollaborationSpaceInboxRepairSignals([redacted])"
}
