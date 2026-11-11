package dev.fajar.starter.collaboration.domain.entities

/** One proposed queue transaction. Admission, CAS/readback and scheduling belong to its owner. */
data class CollaborationSpaceInventoryRetention(
    val demand: CollaborationSpaceIncomingDemand,
    val outcome: CollaborationSpaceInventoryOutcome,
    val acceptedRooms: Int,
) {
    override fun toString() = "CollaborationSpaceInventoryRetention([redacted])"
}

enum class CollaborationSpaceInventoryOutcome {
    Advanced,
    Complete,
    WaitingForRoom,
    Capacity,
    Obsolete,
    Deferred,
}
