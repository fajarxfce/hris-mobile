package dev.fajar.starter.collaboration.domain.entities

/**
 * Visibility of a Matrix read receipt; both choices advance only the caller's own read position.
 */
enum class CollaborationReadReceiptVisibility {
    Public,
    Private,
}

/** A native-store observation, not a read-order comparison or notification display permission. */
data class CollaborationOwnReadReceipts(val publicEventId: String?, val privateEventId: String?) {
    override fun toString() = "CollaborationOwnReadReceipts([redacted])"
}
