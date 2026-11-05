package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageReference

fun validCollaborationVisibilitySelection(
    conversationId: String,
    roomId: String,
    messages: List<CollaborationMessageReference>,
    limit: Int,
    allowEmpty: Boolean,
) =
    validCollaborationMessageId(conversationId) &&
        roomId.startsWith('!') &&
        roomId.length in 2..255 &&
        roomId.none { it.isWhitespace() || it.isISOControl() } &&
        messages.size in (if (allowEmpty) 0 else 1)..limit &&
        messages.distinct().size == messages.size &&
        messages.all(::validCollaborationMessageReference)

fun validCollaborationMessageReference(value: CollaborationMessageReference): Boolean =
    validCollaborationHistorySender(value.sender) &&
        validCollaborationEventId(value.eventId) &&
        (value.logicalId == null || validCollaborationMessageId(value.logicalId))
