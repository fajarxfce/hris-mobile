package dev.fajar.starter.collaboration.domain.entities

/** Exact locally retained coverage; no timestamp or provider-order inference. */
data class CollaborationReadStateRevision(val generation: String, val revision: Long)

data class CollaborationReadReceiptIntent(
    val id: String,
    val eventId: String,
    val visibility: CollaborationReadReceiptVisibility,
) {
    override fun toString() = "CollaborationReadReceiptIntent([redacted])"
}

data class CollaborationReadState(
    val version: CollaborationReadStateRevision?,
    val readEventIds: Set<String>,
    val pending: List<CollaborationReadReceiptIntent>,
    val acknowledged: CollaborationReadReceiptIntent?,
) {
    override fun toString() = "CollaborationReadState([redacted])"
}
