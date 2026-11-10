package dev.fajar.starter.collaboration.domain.entities

/** The next raw inventory position commits in the same document as the rooms it discovers. */
data class CollaborationSpaceIncomingScan(
    val generation: String,
    val cursor: CollaborationSpaceRoomBindingCursor? = null,
    val hasNewerBindings: Boolean = false,
) {
    override fun toString() = "CollaborationSpaceIncomingScan([redacted])"
}
