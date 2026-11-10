package dev.fajar.starter.collaboration.domain.entities

/** A group wake hint, not content authority or a Matrix key-publication revision. */
data class CollaborationSpacePushSignal(
    val eventId: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val revision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val roomId: String?,
    val matrixEventId: String?,
) {
    override fun toString() = "CollaborationSpacePushSignal([redacted])"
}
