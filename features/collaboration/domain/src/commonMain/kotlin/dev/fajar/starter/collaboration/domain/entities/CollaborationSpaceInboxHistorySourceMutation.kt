package dev.fajar.starter.collaboration.domain.entities

/**
 * Domain supplies admitted transitions; storage owns exact CAS, encryption and reference indexes.
 */
data class CollaborationSpaceInboxHistorySourceMutation(
    val expected: CollaborationSpaceInboxHistorySourceCheckpoint?,
    val replacement: CollaborationSpaceInboxHistorySourceProgress,
    val destination: CollaborationSpaceInboxVersion?,
    val retainedEventIds: List<String>,
    val unresolvedEventIds: List<String>,
) {
    override fun toString() = "CollaborationSpaceInboxHistorySourceMutation([redacted])"
}

data class CollaborationSpaceInboxHistorySourcePage(
    val sources: List<CollaborationSpaceInboxHistorySourceCheckpoint>,
    val next: String?,
) {
    override fun toString() = "CollaborationSpaceInboxHistorySourcePage([redacted])"
}

data class CollaborationSpaceInboxHistoryPendingPage(
    val version: CollaborationSpaceInboxHistoryProgressVersion,
    val window: CollaborationSpaceInboxHistoryPendingWindow,
    val references: List<CollaborationSpaceInboxHistoryRepairReference>,
    val next: String?,
) {
    override fun toString() = "CollaborationSpaceInboxHistoryPendingPage([redacted])"
}
