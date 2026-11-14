package dev.fajar.starter.collaboration.domain.entities

/**
 * Each action carries a captured destination; successful source visits use their exact checkpoint
 * guard.
 */
sealed interface CollaborationSpaceInboxHistoryChange {
    val destination: CollaborationSpaceInboxVersion?

    data class Observe(
        val trigger: CollaborationSpaceInboxHistoryTrigger,
        val newPassId: String,
        override val destination: CollaborationSpaceInboxVersion?,
    ) : CollaborationSpaceInboxHistoryChange

    data class CaptureCatalog(
        val page: CollaborationSpaceConversationCatalogRead.Page,
        override val destination: CollaborationSpaceInboxVersion?,
    ) : CollaborationSpaceInboxHistoryChange

    data class CaptureSources(
        val page: CollaborationSpaceInboxHistorySourcePage,
        override val destination: CollaborationSpaceInboxVersion?,
    ) : CollaborationSpaceInboxHistoryChange

    data class AcknowledgeSource(
        val conversationId: String,
        val before: CollaborationSpaceInboxHistorySourceCheckpoint?,
        val after: CollaborationSpaceInboxHistorySourceCheckpoint?,
        override val destination: CollaborationSpaceInboxVersion?,
    ) : CollaborationSpaceInboxHistoryChange

    data class RestartCatalog(override val destination: CollaborationSpaceInboxVersion?) :
        CollaborationSpaceInboxHistoryChange

    data class Resume(override val destination: CollaborationSpaceInboxVersion?) :
        CollaborationSpaceInboxHistoryChange

    data class Complete(override val destination: CollaborationSpaceInboxVersion?) :
        CollaborationSpaceInboxHistoryChange
}
