package dev.fajar.starter.collaboration.domain.entities

/** Each operation can be attempted once in a source visit, even when it fails. */
enum class CollaborationSpaceInboxHistoryVisitOperation {
    Prepare,
    History,
    Pending,
}

sealed interface CollaborationSpaceInboxHistorySourceStep {
    data class Prepare(val conversationId: String, val passId: String) :
        CollaborationSpaceInboxHistorySourceStep {
        override fun toString() = "Prepare([redacted])"
    }

    data class History(val expected: CollaborationSpaceInboxHistorySourceCheckpoint) :
        CollaborationSpaceInboxHistorySourceStep {
        override fun toString() = "History([redacted])"
    }

    data class Pending(val expected: CollaborationSpaceInboxHistorySourceCheckpoint) :
        CollaborationSpaceInboxHistorySourceStep {
        override fun toString() = "Pending([redacted])"
    }

    /** End this visit; the global acknowledgement decides whether another round is useful. */
    data class Yield(val retained: CollaborationSpaceInboxHistorySourceCheckpoint?) :
        CollaborationSpaceInboxHistorySourceStep {
        override fun toString() = "Yield([redacted])"
    }
}
