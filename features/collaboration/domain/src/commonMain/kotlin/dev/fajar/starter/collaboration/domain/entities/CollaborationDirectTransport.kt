package dev.fajar.starter.collaboration.domain.entities

/** A current native metadata read, not a cached permission or SDK credential. */
data class CollaborationDirectTransport(
    val owner: CollaborationOwner,
    val deviceId: String,
    val deviceVersion: Long,
    val platform: CollaborationNativePlatform,
    val conversationId: String,
    val firstAccountId: String,
    val secondAccountId: String,
    val matrixRoomId: String,
    val roomProvisioningVersion: Long,
) {
    override fun toString() = "CollaborationDirectTransport(redacted)"
}
