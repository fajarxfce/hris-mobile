package dev.fajar.starter.collaboration.domain.entities

/** Provider traversal facts, not decrypted content or an acknowledgement that it was stored. */
data class CollaborationProviderHistoryEvent(
    val eventId: String,
    val sender: String,
    val timestamp: Long,
    val type: String,
) {
    override fun toString() = "CollaborationProviderHistoryEvent([redacted])"
}

/** Positions belong to the admitted provider/device/room. Null next alone means no continuation. */
data class CollaborationProviderHistoryPage(
    val owner: CollaborationOwner,
    val deviceId: String,
    val roomId: String,
    val requestedPosition: String?,
    val start: String,
    val next: String?,
    /** Backward provider order; timestamps must not be used to reconstruct traversal order. */
    val events: List<CollaborationProviderHistoryEvent>,
    /** Exclusive older bound for one incoming wave; owned provider positions, never timestamps. */
    val requestedUntilPosition: String? = null,
) {
    override fun toString() = "CollaborationProviderHistoryPage([redacted])"
}
