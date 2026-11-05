package dev.fajar.starter.collaboration.domain.policies

fun validCollaborationQuoteQuery(conversationId: String, roomId: String, eventIds: List<String>) =
    validCollaborationMessageId(conversationId) &&
        roomId.startsWith('!') &&
        roomId.length in 2..255 &&
        roomId.none { it.isWhitespace() || it.isISOControl() } &&
        eventIds.size <= 128 &&
        eventIds.distinct().size == eventIds.size &&
        eventIds.all(::validCollaborationEventId)
