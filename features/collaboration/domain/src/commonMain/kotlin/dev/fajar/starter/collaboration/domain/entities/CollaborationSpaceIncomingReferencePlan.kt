package dev.fajar.starter.collaboration.domain.entities

/** Exact source resolution and independent pending work; never an outgoing receipt. */
data class CollaborationSpaceIncomingReferencePlan(
    val resolved: List<CollaborationHistoryReference>,
    val unresolved: Int,
    val more: Boolean,
    val retryRequired: Boolean,
) {
    override fun toString() = "CollaborationSpaceIncomingReferencePlan([redacted])"
}

/** Local completion of one bounded attempt; its worker still owns durable retry scheduling. */
data class CollaborationSpaceIncomingReferenceCompletion(
    val resolved: Int,
    val unresolved: Int,
    val more: Boolean,
    val retryRequired: Boolean,
)
