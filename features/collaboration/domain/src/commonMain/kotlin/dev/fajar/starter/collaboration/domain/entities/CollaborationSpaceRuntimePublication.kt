package dev.fajar.starter.collaboration.domain.entities

/** Ephemeral identity of a published installed client; never a permission or a close handle. */
data class CollaborationSpaceRuntimePublication(
    val runtime: CollaborationSpaceRuntimeReady,
    val instanceId: String,
) {
    override fun toString() = "CollaborationSpaceRuntimePublication([redacted])"
}
