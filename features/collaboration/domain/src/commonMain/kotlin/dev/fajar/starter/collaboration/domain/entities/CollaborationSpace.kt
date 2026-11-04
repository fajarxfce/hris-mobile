package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant
import kotlin.time.TimeMark

/** Current organization request owner. This is never an HR company or a physical SDK store. */
data class CollaborationSpaceOwner(val parent: CollaborationNativeParent, val spaceId: String) {
    override fun toString() = "CollaborationSpaceOwner([redacted])"
}

/** Remote observation only; it does not grant a provider device, room or local read. */
data class CollaborationSpaceObservation(
    val owner: CollaborationSpaceOwner,
    val nativeSessionVersion: Long,
    val credentialVersion: Long,
    val admittedAt: Instant,
    val expiresAt: Instant,
)

/** Ephemeral discovery lifetime; subsequent operations reacquire current server admission. */
data class CollaborationSpaceDiscovery(
    val observation: CollaborationSpaceObservation,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val monotonicDeadline: TimeMark,
) {
    val owner
        get() = observation.owner

    override fun toString() = "CollaborationSpaceDiscovery([redacted])"
}

/** Public company labels grant no access to HR records in those companies. */
data class CollaborationSpaceContactCompany(val id: String, val name: String)

data class CollaborationSpaceContact(
    val accountId: String,
    val displayName: String,
    val companies: List<CollaborationSpaceContactCompany>,
    val hasMoreCompanies: Boolean,
)

data class CollaborationSpaceContactPage(
    val owner: CollaborationSpaceOwner,
    val items: List<CollaborationSpaceContact>,
    val nextCursor: String?,
)
