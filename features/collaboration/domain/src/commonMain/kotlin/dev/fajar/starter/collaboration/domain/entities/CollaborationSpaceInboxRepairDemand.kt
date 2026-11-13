package dev.fajar.starter.collaboration.domain.entities

/** Exact original coordinates only; a target carries no copied message body or delivery claim. */
data class CollaborationSpaceInboxRepairTarget(val conversationId: String, val messageId: String) {
    override fun toString() = "CollaborationSpaceInboxRepairTarget([redacted])"
}

/** Bounded immediate work plus one coalescible finite-pass request. */
data class CollaborationSpaceInboxRepairDemand(
    val targets: List<CollaborationSpaceInboxRepairTarget>,
    val requiresScan: Boolean,
) {
    override fun toString() = "CollaborationSpaceInboxRepairDemand([redacted])"
}
