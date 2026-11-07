package dev.fajar.starter.collaboration.domain.entities

/** Owned setup facts only. Neither case grants current room or content access. */
sealed interface CollaborationSpaceOpenPreparation {
    data class Canonical(val conversationId: String) : CollaborationSpaceOpenPreparation

    data class Original(val record: CollaborationSpaceConversationCommandRecord) :
        CollaborationSpaceOpenPreparation
}
