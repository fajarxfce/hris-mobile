package dev.fajar.starter.collaboration.domain.entities

/** A decrypted replacement event. It never replaces original send/receipt evidence. */
data class CollaborationMessageEdit(
    val eventId: String,
    val sender: String,
    val targetEventId: String,
    val timestamp: Long,
    val text: String,
    /** Fingerprint of the complete replacement content, including unknown fields. */
    val contentHash: String,
    /** Separate from the original message identity; absent for a non-HRIS replacement. */
    val operationId: String? = null,
    val originalLogicalId: String? = null,
    val replyToEventId: String? = null,
    val forwarded: Boolean = false,
) {
    override fun toString() = "CollaborationMessageEdit([redacted])"
}
