package dev.fajar.starter.collaboration.domain.entities

/** An opaque committed-publication hint; never permission to read or send content. */
data class CollaborationReadinessControl(
    val eventId: String,
    val companyId: String,
    val accountId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val publicationRevision: Long,
    val publishedAtEpochMillis: Long,
) {
    override fun toString() = "CollaborationReadinessControl([redacted])"
}

/** The exact native client supplies its source owner; packet content cannot select that owner. */
data class CollaborationReadinessSignal(
    val sourceOwner: CollaborationOwner,
    val sourceDeviceId: String,
    val control: CollaborationReadinessControl,
) {
    override fun toString() = "CollaborationReadinessSignal([redacted])"
}

data class CollaborationReadinessScope(
    val parent: CollaborationNativeParent,
    val companyId: String,
    val deviceId: String,
) {
    override fun toString() = "CollaborationReadinessScope([redacted])"
}

/** Pending is retained before scheduling; requested advances only after a durable recovery wake. */
data class CollaborationReadinessProgress(val requestedRevision: Long, val pendingRevision: Long)

data class CollaborationReadinessReceipt(
    val generation: String,
    val progress: CollaborationReadinessProgress,
) {
    override fun toString() = "CollaborationReadinessReceipt([redacted])"
}
