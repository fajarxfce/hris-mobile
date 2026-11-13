package dev.fajar.starter.collaboration.domain.entities

/** Identity selection only. It neither allocates an inbox anchor nor acknowledges delivery. */
sealed interface CollaborationSpaceInboxHistorySource {
    data class Original(val association: CollaborationSpaceInboxOriginalAssociation) :
        CollaborationSpaceInboxHistorySource {
        override fun toString() = "CollaborationSpaceInboxHistorySource.Original([redacted])"
    }

    data class History(val source: CollaborationSpaceInboxSource.History) :
        CollaborationSpaceInboxHistorySource {
        override fun toString() = "CollaborationSpaceInboxHistorySource.History([redacted])"
    }

    /** No identity may be published. The owner retains work without starting a polling retry. */
    data class Unresolved(val reason: CollaborationSpaceInboxHistorySourceReason) :
        CollaborationSpaceInboxHistorySource
}

enum class CollaborationSpaceInboxHistorySourceReason {
    ORIGINAL_NOT_PROVEN,
    MULTIPLE_ORIGINALS,
}
