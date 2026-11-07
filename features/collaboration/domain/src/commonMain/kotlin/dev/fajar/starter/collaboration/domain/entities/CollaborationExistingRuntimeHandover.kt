package dev.fajar.starter.collaboration.domain.entities

/** Exact original local storage and target; no permission for server freeze or SDK adoption. */
data class CollaborationExistingRuntimeHandover(
    val original: CollaborationSpaceLegacySdk,
    val intent: CollaborationRuntimeHandoverIntent,
) {
    override fun toString() = "CollaborationExistingRuntimeHandover([redacted])"
}
