package dev.fajar.starter.collaboration.domain.entities

/** Complete immutable server fingerprint, independent of a replacement local login handle. */
data class CollaborationSpaceRoomOperation(
    val operationId: String,
    val binding: CollaborationSpaceIdentityBinding,
    val conversationId: String,
    val nativeSessionVersion: Long,
    val request: CollaborationSpaceRoomRequest,
    val platform: CollaborationNativePlatform,
    val clientBuild: Long,
) {
    override fun toString() = "CollaborationSpaceRoomOperation([redacted])"
}

/** A missing receipt is uncertain: it neither fences the original nor permits a replacement. */
data class CollaborationSpaceRoomReceiptObservation(
    val original: CollaborationSpaceRoomOperation,
    val receipt: CollaborationSetupReceipt?,
)
