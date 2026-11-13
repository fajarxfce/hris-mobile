package dev.fajar.starter.collaboration.domain.entities

/** One published generation. lastOrdinal is a durable insertion counter, independent of time. */
data class CollaborationSpaceInboxVersion(
    val generation: String,
    val revision: Long,
    val lastOrdinal: Long,
)

data class CollaborationSpaceInboxActivities(
    val version: CollaborationSpaceInboxVersion?,
    val values: List<CollaborationSpaceInboxActivity>,
) {
    override fun toString() = "CollaborationSpaceInboxActivities([redacted])"
}

data class CollaborationSpaceInboxConversation(
    val version: CollaborationSpaceInboxVersion?,
    val entry: CollaborationSpaceInboxEntry?,
    val draft: CollaborationSpaceInboxDraft?,
    /** Bounded preceding visible candidates, already ordered by their retained position. */
    val candidates: List<CollaborationSpaceInboxActivity>,
) {
    override fun toString() = "CollaborationSpaceInboxConversation([redacted])"
}

/** Ready domain proposal. Source owners can encrypt this as the second atomic replica mutation. */
data class CollaborationSpaceInboxPublication(
    val expected: CollaborationSpaceInboxVersion?,
    val lastOrdinal: Long,
    val entry: CollaborationSpaceInboxEntry,
    /** Null means unchanged; an empty draft is an explicit clearing revision. */
    val draft: CollaborationSpaceInboxDraft? = null,
    /** Hidden replacements preserve anchors; no arbitrary activity deletion is exposed. */
    val activities: List<CollaborationSpaceInboxActivity> = emptyList(),
    /** Original retention/completion participates in this same index transaction. */
    val repair: CollaborationSpaceInboxRepairChange? = null,
) {
    override fun toString() = "CollaborationSpaceInboxPublication([redacted])"
}

sealed interface CollaborationSpaceInboxPage {
    /** A recency index moved after capture; restarting avoids skipped/repeated mutable rows. */
    data object Restart : CollaborationSpaceInboxPage

    data class Items(
        val version: CollaborationSpaceInboxVersion?,
        val entries: List<CollaborationSpaceInboxEntry>,
        val next: String?,
    ) : CollaborationSpaceInboxPage {
        override fun toString() = "CollaborationSpaceInboxPage([redacted])"
    }
}

/** A replay is confirmed before reading repair progress; a new seed requires atomic demand. */
enum class CollaborationSpaceInboxSeedResult {
    RETAINED,
    STORED,
    DEMAND_REQUIRED,
    CHANGED,
}
