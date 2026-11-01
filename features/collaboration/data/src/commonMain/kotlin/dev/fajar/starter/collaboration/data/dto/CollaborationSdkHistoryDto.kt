package dev.fajar.starter.collaboration.data.dto

/** Detached SDK primitives. These must originate from original_json, not latest_json. */
internal data class CollaborationSdkHistoryEventDto(
    val eventId: String,
    val sender: String,
    val timestamp: Long,
    val kind: SdkMessageKind,
    val originalJson: String?,
) {
    override fun toString() = "CollaborationSdkHistoryEventDto([redacted])"
}
