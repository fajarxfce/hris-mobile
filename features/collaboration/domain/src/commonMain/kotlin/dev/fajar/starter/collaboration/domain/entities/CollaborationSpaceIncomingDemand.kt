package dev.fajar.starter.collaboration.domain.entities

/** A metadata wave starts once; missing-key retries resume the saved pending-reference pass. */
enum class CollaborationSpaceIncomingPhase {
    Latest,
    Provider,
    Pending,
}

/** Immutable invocation capture. A later request for the same room has a different generation. */
data class CollaborationSpaceIncomingRoomDemand(
    val conversationId: String,
    val generation: String,
    val position: Long,
    val headEventId: String?,
    val phase: CollaborationSpaceIncomingPhase,
    val retry: CollaborationSpaceIncomingRetry? = null,
    val sourceId: String? = null,
) {
    override fun toString() = "CollaborationSpaceIncomingRoomDemand([redacted])"
}

/**
 * Account/space-scoped scheduling facts, not authority to read content or open an SDK. The
 * repository must retain changed demand and its independent scheduling wake atomically.
 */
data class CollaborationSpaceIncomingDemand(
    val nextPosition: Long = 0,
    val rooms: List<CollaborationSpaceIncomingRoomDemand> = emptyList(),
    val rescanGeneration: String? = null,
    val scan: CollaborationSpaceIncomingScan? = null,
    val sources: List<CollaborationSpaceIncomingSourceProgress> = emptyList(),
    val physicalRooms: List<CollaborationSpaceIncomingPhysicalRoomDemand> = emptyList(),
) {
    override fun toString() = "CollaborationSpaceIncomingDemand([redacted])"
}

sealed interface CollaborationSpaceIncomingDemandChange {
    /** Caller has admitted this current hint and excluded an already-retained, idle head. */
    data class Request(
        val conversationId: String,
        val generation: String,
        val headEventId: String?,
    ) : CollaborationSpaceIncomingDemandChange

    /** A finite provider page committed; only the captured generation may advance its phase. */
    data class ProviderAcquired(
        val captured: CollaborationSpaceIncomingRoomDemand,
        val exhausted: Boolean,
    ) : CollaborationSpaceIncomingDemandChange

    /** Preserve the current phase and move behind the captured turn's FIFO boundary. */
    data class Defer(
        val captured: CollaborationSpaceIncomingRoomDemand,
        val retry: CollaborationSpaceIncomingRetry? = null,
    ) : CollaborationSpaceIncomingDemandChange

    /** Current provider absence restarts immediately; an unseen known head retains a delay. */
    data class RestartProvider(
        val captured: CollaborationSpaceIncomingRoomDemand,
        val retry: CollaborationSpaceIncomingRetry?,
    ) : CollaborationSpaceIncomingDemandChange

    /** Only the owning completion usecase may admit clean provider EOF and no pending sources. */
    data class Complete(val captured: CollaborationSpaceIncomingRoomDemand) :
        CollaborationSpaceIncomingDemandChange

    data class Rescan(val generation: String) : CollaborationSpaceIncomingDemandChange

    /** Only the exact completed inventory scan can discharge its captured generation. */
    data class CompleteRescan(val generation: String) : CollaborationSpaceIncomingDemandChange
}
