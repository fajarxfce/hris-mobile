package dev.fajar.starter.collaboration.domain.entities

/** Sensitive bootstrap result. Only the owning SDK/session store consumes credentials. */
class CollaborationGateway(
    val owner: CollaborationOwner,
    val deviceId: String,
    val sessionId: String,
    val version: Long,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    override fun toString() = "CollaborationGateway([redacted])"
}
