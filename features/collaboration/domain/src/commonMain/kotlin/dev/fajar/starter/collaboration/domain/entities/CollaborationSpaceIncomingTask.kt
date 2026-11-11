package dev.fajar.starter.collaboration.domain.entities

/** A selected task retains its position in the shared logical/physical FIFO. */
sealed interface CollaborationSpaceIncomingTask {
    val position: Long

    data class Logical(val captured: CollaborationSpaceIncomingRoomDemand) :
        CollaborationSpaceIncomingTask {
        override val position
            get() = captured.position
    }

    data class Physical(val captured: CollaborationSpaceIncomingPhysicalRoomDemand) :
        CollaborationSpaceIncomingTask {
        override val position
            get() = captured.position
    }
}
