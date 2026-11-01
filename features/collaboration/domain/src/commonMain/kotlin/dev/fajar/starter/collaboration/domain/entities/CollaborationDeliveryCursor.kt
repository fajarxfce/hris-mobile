package dev.fajar.starter.collaboration.domain.entities

/** Generation makes a changed cursor observable even after an A-to-B-to-A rotation. */
data class CollaborationDeliveryCursor(val generation: String, val afterConversationId: String) {
    override fun toString() = "CollaborationDeliveryCursor([redacted])"
}
