package dev.fajar.starter.collaboration.data.dto

/** Exact native store bytes. No acceptance or ownership decision belongs to this DTO. */
internal data class CollaborationQueuedOriginalDto(
    val transactionId: String,
    val eventType: String,
    val contentJson: String,
) {
    override fun toString() = "CollaborationQueuedOriginalDto([redacted])"
}
