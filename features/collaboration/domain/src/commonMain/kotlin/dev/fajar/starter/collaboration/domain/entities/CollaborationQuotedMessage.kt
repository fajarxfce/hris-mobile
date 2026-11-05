package dev.fajar.starter.collaboration.domain.entities

/**
 * Visible original quote from this device's admitted conversation; never a body snapshot in a
 * draft.
 */
data class CollaborationQuotedMessage(val eventId: String, val sender: String, val text: String) {
    override fun toString() = "CollaborationQuotedMessage([redacted])"
}
