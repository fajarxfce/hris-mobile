package dev.fajar.starter.collaboration.domain.entities

/** Stable request body; the retained origin parent does not become current authentication. */
data class CollaborationSpaceRoomRequestIntent(
    val operationId: String,
    val binding: CollaborationSpaceIdentityBinding,
    val conversationId: String,
    val request: CollaborationSpaceRoomRequest,
) {
    override fun toString() = "CollaborationSpaceRoomRequestIntent([redacted])"
}

/** Null remains uncertain and grants no replacement or provider authority. */
data class CollaborationSpaceRoomIntentReceiptObservation(
    val original: CollaborationSpaceRoomRequestIntent,
    val receipt: CollaborationSetupReceipt?,
)
