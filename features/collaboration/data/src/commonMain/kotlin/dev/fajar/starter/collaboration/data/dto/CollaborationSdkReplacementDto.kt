package dev.fajar.starter.collaboration.data.dto

/** Detached replacement JSON bound to the original SDK item's room-owned identity. */
internal data class CollaborationSdkReplacementDto(
    val targetEventId: String,
    val sender: String,
    val json: String,
) {
    override fun toString() = "CollaborationSdkReplacementDto([redacted])"
}
