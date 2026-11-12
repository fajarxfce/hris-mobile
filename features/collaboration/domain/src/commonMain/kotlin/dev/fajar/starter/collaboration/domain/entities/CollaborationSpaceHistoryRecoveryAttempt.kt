package dev.fajar.starter.collaboration.domain.entities

/** The application supplies only admitted local results; global completion reconfirms ownership. */
sealed interface CollaborationSpaceHistoryRecoveryAttempt {
    /** Completion must independently confirm unavailable source or metadata in the actual scope. */
    data object Unavailable : CollaborationSpaceHistoryRecoveryAttempt

    data class Original(
        val history: CollaborationSpaceOriginalKeyHistory,
        val read: CollaborationHistoryRecoveryRead,
        val completion: CollaborationHistoryRecoveryPageCompletion? = null,
    ) : CollaborationSpaceHistoryRecoveryAttempt {
        override fun toString() = "CollaborationSpaceHistoryRecoveryAttempt.Original([redacted])"
    }

    data class Published(
        val history: CollaborationSpaceKeyHistory,
        val read: CollaborationHistoryRecoveryRead,
        val completion: CollaborationHistoryRecoveryPageCompletion? = null,
    ) : CollaborationSpaceHistoryRecoveryAttempt {
        override fun toString() = "CollaborationSpaceHistoryRecoveryAttempt.Published([redacted])"
    }
}
