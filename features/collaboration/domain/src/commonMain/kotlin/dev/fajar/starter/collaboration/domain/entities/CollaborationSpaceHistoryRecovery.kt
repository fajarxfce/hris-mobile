package dev.fajar.starter.collaboration.domain.entities

/** A constant account/root-owned metadata pass; retained coordinates grant no native authority. */
data class CollaborationSpaceHistoryRecovery(
    val generation: String,
    val revision: Long = 0,
    val afterPartition: String? = null,
    val moreHistoryPages: Boolean = false,
    val unresolved: Boolean = false,
    val complete: Boolean = false,
    val requestedGeneration: String? = null,
    val retry: CollaborationSpaceIncomingRetry? = null,
) {
    override fun toString() = "CollaborationSpaceHistoryRecovery([redacted])"
}
