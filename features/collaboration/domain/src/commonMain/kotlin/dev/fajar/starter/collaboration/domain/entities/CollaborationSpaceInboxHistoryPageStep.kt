package dev.fajar.starter.collaboration.domain.entities

sealed interface CollaborationSpaceInboxHistoryPageStep {
    data class Restart(
        val expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        val read: CollaborationSpaceHistoryChangesRead,
    ) : CollaborationSpaceInboxHistoryPageStep {
        override fun toString() = "Restart([redacted])"
    }

    data class Confirm(
        val expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        val read: CollaborationSpaceHistoryChangesRead,
    ) : CollaborationSpaceInboxHistoryPageStep {
        override fun toString() = "Confirm([redacted])"
    }

    data class PublishHistory(
        val expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        val read: CollaborationSpaceHistoryChangesRead,
        val eventIds: List<String>,
    ) : CollaborationSpaceInboxHistoryPageStep {
        override fun toString() = "PublishHistory([redacted])"
    }

    data class CompletePending(
        val expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        val read: CollaborationSpaceInboxHistoryPendingPage,
    ) : CollaborationSpaceInboxHistoryPageStep {
        override fun toString() = "CompletePending([redacted])"
    }

    data class PublishPending(
        val expected: CollaborationSpaceInboxHistorySourceCheckpoint,
        val read: CollaborationSpaceInboxHistoryPendingPage,
        val eventIds: List<String>,
    ) : CollaborationSpaceInboxHistoryPageStep {
        override fun toString() = "PublishPending([redacted])"
    }
}
