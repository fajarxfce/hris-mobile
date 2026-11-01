package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationHistoryProgress(progress: CollaborationHistoryProgress): Boolean {
    val next = progress.next
    return validCollaborationMessageId(progress.generation) &&
        progress.revision in 0 until 9_007_199_254_740_991L &&
        progress.references in 0..9_007_199_254_740_991L &&
        (next == null || validCollaborationProviderPosition(next))
}

fun validCollaborationProviderPosition(value: String) =
    value.length in 1..4096 && value.all { it.code in 33..126 }

fun validCollaborationProviderEvent(event: CollaborationProviderHistoryEvent) =
    validCollaborationEventId(event.eventId) &&
        validCollaborationHistorySender(event.sender) &&
        event.timestamp in 0..9_007_199_254_740_991L &&
        event.type.length in 1..255 &&
        event.type.none { it.isWhitespace() || it.isISOControl() }

fun validCollaborationHistoryTraversal(key: CollaborationHistoryTraversal) =
    validCollaborationMessageId(key.conversationId) &&
        validCollaborationMessageId(key.deviceId) &&
        key.roomId.startsWith('!') &&
        key.roomId.length in 2..255 &&
        key.roomId.none { it.isWhitespace() || it.isISOControl() }

fun validCollaborationProviderPage(
    page: CollaborationProviderHistoryPage,
    access: CollaborationLocalAccess,
    traversal: CollaborationHistoryTraversal,
): Boolean =
    validCollaborationHistoryTraversal(traversal) &&
        page.owner.sessionId == access.sessionId &&
        page.owner.audience == access.audience &&
        page.owner.accountId == access.accountId &&
        page.owner.companyId == access.companyId &&
        page.roomId == traversal.roomId &&
        page.deviceId == traversal.deviceId &&
        validCollaborationProviderPosition(page.start) &&
        (page.requestedPosition == null ||
            validCollaborationProviderPosition(page.requestedPosition)) &&
        (page.next == null ||
            (validCollaborationProviderPosition(page.next) &&
                page.next != page.start &&
                page.next != page.requestedPosition)) &&
        page.events.size <= 20 &&
        page.events.all(::validCollaborationProviderEvent) &&
        page.events.map { it.eventId }.distinct().size == page.events.size
