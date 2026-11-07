package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Revisions are private to each participant; retain the account and revision together. */
data class CollaborationSpaceRoomAttempt(val accountId: String, val revision: Long)

data class CollaborationSpaceRoomRequest(
    val deviceId: String,
    val expectedDeviceVersion: Long,
    val expectedAttempt: CollaborationSpaceRoomAttempt? = null,
)

/** Public operational metadata, never SDK credentials or a live content authorization. */
data class CollaborationSpaceRoomView(
    val conversation: CollaborationSpaceConversation,
    val provisioning: CollaborationSpaceRoomStatus?,
    val room: CollaborationSpacePublishedRoom?,
)

data class CollaborationSpaceRoomStatus(
    val attempt: CollaborationSpaceRoomAttempt,
    val state: CollaborationProvisioningState,
    val attempts: Int,
    val requestedAt: Instant,
    val availableAt: Instant,
    val expiresAt: Instant,
    val completedAt: Instant?,
    val failure: CollaborationSpaceRoomFailure?,
    val lastOutcome: CollaborationSpaceRoomOutcome?,
)

data class CollaborationSpaceRoomOutcome(
    val revision: Long,
    val state: CollaborationProvisioningState,
    val at: Instant,
    val failure: CollaborationSpaceRoomFailure?,
)

data class CollaborationSpacePublishedRoom(
    val roomId: String,
    val serverName: String,
    val origin: CollaborationSpaceRoomOrigin,
    val publishedAt: Instant,
)

enum class CollaborationSpaceRoomOrigin {
    SPACE,
    LEGACY,
}

enum class CollaborationSpaceRoomFailure {
    REQUEST_EXPIRED,
    ATTEMPTS_EXHAUSTED,
    ACCESS_DENIED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_IDENTITY_CHANGED,
    PUBLICATION_CHANGED,
    ROOM_MISMATCH,
}
