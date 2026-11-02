package dev.fajar.starter.collaboration.domain.entities

/** Bounded scheduling metadata only. None of these values admits content or navigation. */
data class CollaborationPushHint(
    val eventId: String,
    val companyId: String,
    val deviceId: String,
    val revision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val roomId: String?,
    val matrixEventId: String?,
) {
    override fun toString() = "CollaborationPushHint([redacted])"
}

data class CollaborationPushPriority(
    val generation: String,
    val hints: List<CollaborationPushHint>,
) {
    override fun toString() = "CollaborationPushPriority([redacted])"
}

/** Local native-family ownership, independent of credential freshness. */
data class CollaborationPushSnapshot(
    val parent: CollaborationNativeParent,
    val priority: CollaborationPushPriority?,
) {
    override fun toString() = "CollaborationPushSnapshot([redacted])"
}
