package dev.fajar.starter.collaboration.domain.entities

/** One local action chosen from retained state. The application owner performs and cancels it. */
sealed interface CollaborationSpaceInboxHistoryStep {
    data class Change(
        val expected: CollaborationSpaceInboxHistoryCheckpoint?,
        val change: CollaborationSpaceInboxHistoryChange,
    ) : CollaborationSpaceInboxHistoryStep {
        override fun toString() = "Change([redacted])"
    }

    data class Catalog(val expected: CollaborationSpaceInboxHistoryCheckpoint, val after: String?) :
        CollaborationSpaceInboxHistoryStep {
        override fun toString() = "Catalog([redacted])"
    }

    data class Sources(val expected: CollaborationSpaceInboxHistoryCheckpoint, val after: String?) :
        CollaborationSpaceInboxHistoryStep {
        override fun toString() = "Sources([redacted])"
    }

    data class Visit(
        val expected: CollaborationSpaceInboxHistoryCheckpoint,
        val conversationId: String,
        val passId: String,
    ) : CollaborationSpaceInboxHistoryStep {
        override fun toString() = "Visit([redacted])"
    }

    /** Completed unchanged work and failed-only parked rounds do not self-schedule. */
    data object Idle : CollaborationSpaceInboxHistoryStep
}
