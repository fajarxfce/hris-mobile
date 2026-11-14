package dev.fajar.starter.collaboration.domain.entities

/** One captured finite pass; its cursor contains no application delivery status. */
data class CollaborationSpaceInboxRepairPass(val demand: String, val after: String?) {
    override fun toString() = "CollaborationSpaceInboxRepairPass([redacted])"
}

/** Latest demand coalesces while the captured pass keeps its own progress. */
data class CollaborationSpaceInboxRepairProgress(
    val generation: String,
    val latestDemand: String,
    val completedDemand: String?,
    val pass: CollaborationSpaceInboxRepairPass?,
) {
    override fun toString() = "CollaborationSpaceInboxRepairProgress([redacted])"
}

sealed interface CollaborationSpaceInboxRepairProgressChange {
    /** Bootstrap a missing pass; an existing request, cursor or completion remains unchanged. */
    data class EnsureInitialized(val demand: String) : CollaborationSpaceInboxRepairProgressChange

    /** A caller creates a fresh non-reused ID for new demand; exact latest replay is harmless. */
    data class Request(val demand: String) : CollaborationSpaceInboxRepairProgressChange

    data object Begin : CollaborationSpaceInboxRepairProgressChange

    data class Restart(val expected: CollaborationSpaceInboxRepairPass) :
        CollaborationSpaceInboxRepairProgressChange {
        override fun toString() = "Restart([redacted])"
    }

    data class Advance(val expected: CollaborationSpaceInboxRepairPass, val next: String?) :
        CollaborationSpaceInboxRepairProgressChange {
        override fun toString() = "Advance([redacted])"
    }
}
