package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationHistoryProgress(progress: CollaborationHistoryProgress): Boolean {
    val next = progress.next
    return validCollaborationMessageId(progress.generation) &&
        progress.revision in 0 until 9_007_199_254_740_991L &&
        progress.references in 0..9_007_199_254_740_991L &&
        (next == null || validCollaborationProviderPosition(next)) &&
        (progress.provider?.let {
            validCollaborationSpaceConversationId(it.companyId) &&
                validCollaborationSpaceConversationId(it.deviceId)
        } ?: true) &&
        (progress.incoming?.let { incoming ->
            validCollaborationProviderPosition(incoming.head) &&
                (incoming.until == null || validCollaborationProviderPosition(incoming.until)) &&
                validCollaborationHistoryReferenceCursor(incoming.pendingAfter) &&
                ((incoming.pendingAfter == null) == (incoming.pendingThrough == null)) &&
                (incoming.pendingThrough == null ||
                    incoming.pendingThrough in 1..progress.references) &&
                (!incoming.pendingRetryRequired || incoming.pendingThrough != null)
        } ?: true)
}

/** A completed wave promotes its head; restarting an unfinished wave retains its previous bound. */
fun collaborationHistoryUntilPosition(
    traversal: CollaborationHistoryTraversal,
    previous: CollaborationHistoryProgress?,
    origin: CollaborationHistoryPageOrigin,
): String? =
    if (traversal.purpose != CollaborationHistoryPurpose.Incoming) null
    else if (origin == CollaborationHistoryPageOrigin.Latest && previous?.exhausted == true)
        previous.incoming?.head
    else previous?.incoming?.until

fun collaborationIncomingHistoryPosition(
    traversal: CollaborationHistoryTraversal,
    previous: CollaborationHistoryProgress?,
    page: CollaborationProviderHistoryPage,
    origin: CollaborationHistoryPageOrigin,
): CollaborationIncomingHistoryPosition? =
    if (traversal.purpose != CollaborationHistoryPurpose.Incoming) null
    else
        CollaborationIncomingHistoryPosition(
            if (origin == CollaborationHistoryPageOrigin.Latest) page.start
            else previous?.incoming?.head ?: page.start,
            collaborationHistoryUntilPosition(traversal, previous, origin),
            previous?.incoming?.pendingAfter,
            previous?.incoming?.pendingThrough,
            previous?.incoming?.pendingRetryRequired ?: false,
        )

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
        (page.requestedUntilPosition == null ||
            validCollaborationProviderPosition(page.requestedUntilPosition)) &&
        (page.next == null ||
            (validCollaborationProviderPosition(page.next) &&
                page.next != page.start &&
                page.next != page.requestedPosition)) &&
        page.events.size <= 20 &&
        page.events.all(::validCollaborationProviderEvent) &&
        page.events.map { it.eventId }.distinct().size == page.events.size

fun validCollaborationHistoryReferenceCursor(value: String?) =
    value == null || (value.length in 1..200 && value.all { it.code in 33..126 })

/**
 * Later appends cannot extend a pending pass indefinitely. At most one final empty page is needed.
 */
fun boundedCollaborationIncomingReferences(
    page: CollaborationHistoryReferences
): CollaborationHistoryReferences? {
    val progress = page.progress ?: return null
    val incoming = progress.incoming ?: return null
    if (!validCollaborationPendingHistoryReferences(page, incoming.pendingAfter, 20)) return null
    val through = incoming.pendingThrough ?: progress.references
    val events = page.events.takeWhile { it.sequence < through }
    val after =
        if (
            events.size == page.events.size &&
                events.lastOrNull()?.let { it.sequence + 1 < through } == true
        )
            page.after
        else null
    return page.copy(events = events, after = after)
}

fun validCollaborationPendingHistoryReferences(
    page: CollaborationHistoryReferences,
    after: String?,
    limit: Int,
): Boolean {
    if (
        limit !in 1..20 ||
            page.events.size > limit ||
            !validCollaborationHistoryReferenceCursor(after)
    )
        return false
    val progress = page.progress
    if (progress == null) return page.events.isEmpty() && page.after == null
    return validCollaborationHistoryProgress(progress) &&
        page.events.all {
            !it.resolved &&
                it.sequence in 0 until progress.references &&
                validCollaborationProviderEvent(it.event)
        } &&
        page.events.zipWithNext().all { (a, b) -> a.sequence < b.sequence } &&
        page.events.map { it.event.eventId }.distinct().size == page.events.size &&
        validCollaborationHistoryReferenceCursor(page.after) &&
        (page.after == null || (page.after != after && page.events.size == limit))
}
