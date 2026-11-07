package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

/** Retained server evidence that this exact original setup request cannot execute. */
data class CollaborationSpaceRoomRequestFence(
    val original: CollaborationSpaceRoomRequestIntent,
    val id: String,
    val createdAt: Instant,
) {
    override fun toString() = "CollaborationSpaceRoomRequestFence([redacted])"
}

/** Setup resolution only; neither branch grants SDK delivery or message acknowledgement. */
sealed interface CollaborationSpaceRoomRequestResolution {
    val original: CollaborationSpaceRoomRequestIntent

    data class Recorded(
        override val original: CollaborationSpaceRoomRequestIntent,
        val receipt: CollaborationSetupReceipt,
    ) : CollaborationSpaceRoomRequestResolution

    data class Fenced(val evidence: CollaborationSpaceRoomRequestFence) :
        CollaborationSpaceRoomRequestResolution {
        override val original
            get() = evidence.original
    }
}
