package dev.fajar.starter.collaboration.domain.entities

/** Ephemeral scheduling state belongs to one explicitly collected local account/Group lifetime. */
data class CollaborationSpaceInboxHistorySchedule(
    val lastTrigger: CollaborationSpaceInboxHistoryTrigger? = null,
    val continuing: Boolean = false,
    val ownerRecoveryPending: Boolean = true,
    val metadataRecoveryPending: Boolean = false,
) {
    override fun toString() = "CollaborationSpaceInboxHistorySchedule([redacted])"
}

data class CollaborationSpaceInboxHistoryFrame(
    val trigger: CollaborationSpaceInboxHistoryTrigger,
    val destination: CollaborationSpaceInboxVersion?,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryFrame([redacted])"
}

sealed interface CollaborationSpaceInboxHistoryScheduleDecision {
    data object Wait : CollaborationSpaceInboxHistoryScheduleDecision

    data class Run(
        val frame: CollaborationSpaceInboxHistoryFrame,
        val recoverParked: Boolean,
        val consumed: CollaborationSpaceInboxHistorySchedule,
    ) : CollaborationSpaceInboxHistoryScheduleDecision {
        override fun toString() = "Run([redacted])"
    }
}

enum class CollaborationSpaceInboxHistoryTurnStatus {
    Advanced,
    Idle,
    Failed,
    /** A conditional write retained no acknowledgement; wait for real input or owner recovery. */
    Contended,
}
