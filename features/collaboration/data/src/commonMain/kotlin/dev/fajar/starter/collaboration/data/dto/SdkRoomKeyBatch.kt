package dev.fajar.starter.collaboration.data.dto

/**
 * A process-local callback receipt; acknowledgment requires independently confirmed durable work.
 */
internal data class SdkRoomKeyBatch(
    val receiptId: String,
    val roomIds: List<String>,
    val reconcileRequired: Boolean,
) {
    override fun toString() = "SdkRoomKeyBatch([redacted])"
}
