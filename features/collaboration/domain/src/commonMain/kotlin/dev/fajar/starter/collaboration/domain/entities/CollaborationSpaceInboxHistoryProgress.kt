package dev.fajar.starter.collaboration.domain.entities

/** Durable captured facts; physical index revisions do not request another history pass. */
data class CollaborationSpaceInboxHistoryTrigger(
    val historySignal: String?,
    val catalog: CollaborationSpaceConversationCatalogVersion?,
    val completedRepairDemand: String?,
    val destinationGeneration: String?,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryTrigger([redacted])"
}

data class CollaborationSpaceInboxHistoryCompletion(
    val passId: String,
    val trigger: CollaborationSpaceInboxHistoryTrigger,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryCompletion([redacted])"
}

/** At most sixteen IDs; the page continuation advances only after every retained item. */
data class CollaborationSpaceInboxHistoryBatch(val remaining: List<String>, val next: String?) {
    override fun toString() = "CollaborationSpaceInboxHistoryBatch([redacted])"
}

sealed interface CollaborationSpaceInboxHistoryTraversal {
    data class Catalog(val after: String?, val batch: CollaborationSpaceInboxHistoryBatch?) :
        CollaborationSpaceInboxHistoryTraversal {
        override fun toString() = "Catalog([redacted])"
    }

    data class Sources(
        val after: String?,
        val batch: CollaborationSpaceInboxHistoryBatch?,
        val unfinished: Boolean,
        val advanced: Boolean,
    ) : CollaborationSpaceInboxHistoryTraversal {
        override fun toString() = "Sources([redacted])"
    }

    /** Every captured source has finished; a current destination guard is still required. */
    data object Ready : CollaborationSpaceInboxHistoryTraversal

    /** A full round could not advance. Real demand or explicit owner recovery can resume it. */
    data object Parked : CollaborationSpaceInboxHistoryTraversal
}

data class CollaborationSpaceInboxHistoryPass(
    val id: String,
    val trigger: CollaborationSpaceInboxHistoryTrigger,
    val attemptedTrigger: CollaborationSpaceInboxHistoryTrigger,
    val traversal: CollaborationSpaceInboxHistoryTraversal,
    val catalogDeferred: Boolean = false,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryPass([redacted])"
}

data class CollaborationSpaceInboxHistoryProgress(
    val latest: CollaborationSpaceInboxHistoryTrigger,
    val completed: CollaborationSpaceInboxHistoryCompletion?,
    val pass: CollaborationSpaceInboxHistoryPass?,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryProgress([redacted])"
}

/** Physical global version is independent of source history, source progress, and inbox order. */
data class CollaborationSpaceInboxHistoryCheckpoint(
    val version: CollaborationSpaceInboxHistoryProgressVersion,
    val progress: CollaborationSpaceInboxHistoryProgress,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryCheckpoint([redacted])"
}

/** Exactly one external partition can be guarded beside the global checkpoint write. */
sealed interface CollaborationSpaceInboxHistoryGuard {
    data class Destination(val version: CollaborationSpaceInboxVersion?) :
        CollaborationSpaceInboxHistoryGuard

    data class Source(val checkpoint: CollaborationSpaceInboxHistorySourceCheckpoint) :
        CollaborationSpaceInboxHistoryGuard
}

data class CollaborationSpaceInboxHistoryMutation(
    val expected: CollaborationSpaceInboxHistoryCheckpoint?,
    val replacement: CollaborationSpaceInboxHistoryProgress,
    val guard: CollaborationSpaceInboxHistoryGuard,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryMutation([redacted])"
}
