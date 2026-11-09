package dev.fajar.starter.collaboration.domain.entities

/** Current public metadata only; no credential, retained permission or authority to resume. */
data class CollaborationSpaceDirectTransport(
    val conversation: CollaborationSpaceConversation,
    val nativeSessionVersion: Long,
    val device: CollaborationSpaceDeviceStatus,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val room: CollaborationSpacePublishedRoom,
    val recipientReady: Boolean,
) {
    override fun toString() = "CollaborationSpaceDirectTransport([metadata])"
}
