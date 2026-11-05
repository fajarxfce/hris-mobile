package dev.fajar.starter.collaboration.domain.entities

const val COLLABORATION_FORWARD_MESSAGE_LIMIT = 50
const val COLLABORATION_FORWARD_TARGET_LIMIT = 5
const val COLLABORATION_FORWARD_BATCH_LIMIT = 128

/** The body shown at selection; the owning use case verifies it against visible local originals. */
data class CollaborationForwardSource(
    val reference: CollaborationMessageReference,
    val text: String,
) {
    override fun toString() = "CollaborationForwardSource([redacted])"
}

data class CollaborationForwardSelection(
    val conversationId: String,
    val roomId: String,
    val messages: List<CollaborationForwardSource>,
    val destinationIds: List<String>,
) {
    override fun toString() = "CollaborationForwardSelection([redacted])"
}

/** Retain this exact preparation across retries; it does not grant live delivery permission. */
data class CollaborationForwardBatch(
    val access: CollaborationLocalAccess,
    val selection: CollaborationForwardSelection,
    val intents: List<CollaborationMessageIntent>,
) {
    override fun toString() = "CollaborationForwardBatch([redacted])"
}
