package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*

/** A visible admitted bubble can preview immediately while its indexed quote observation opens. */
internal fun ChatThreadState.quote(eventId: String): CollaborationQuotedMessage? {
    quotes
        .firstOrNull { it.eventId == eventId }
        ?.let {
            return it
        }
    val row =
        rows.firstOrNull { it.eventId == eventId && it.kind == CollaborationMessageKind.TEXT }
            ?: return null
    val sender = row.reference?.sender ?: return null
    val text = row.text ?: return null
    return CollaborationQuotedMessage(eventId, sender, text)
}
