package dev.fajar.starter.collaboration.domain.entities

/** One admitted local view; command namespaces retain their original ownership and IDs. */
data class CollaborationSpaceThreadSnapshot(
    val history: CollaborationSpaceThreadHistory,
    val spaceCommands: List<CollaborationSpaceMessageRecord>,
    val originalCommands: List<CollaborationMessageRecord>,
) {
    override fun toString() = "CollaborationSpaceThreadSnapshot([redacted])"
}
