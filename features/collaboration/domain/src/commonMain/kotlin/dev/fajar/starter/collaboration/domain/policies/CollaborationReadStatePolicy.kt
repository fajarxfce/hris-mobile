package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationReadSelection(
    conversationId: String,
    roomId: String,
    eventIds: List<String>,
    allowEmpty: Boolean,
) =
    validCollaborationMessageId(conversationId) &&
        roomId.startsWith('!') &&
        roomId.length in 2..255 &&
        roomId.none { it.isWhitespace() || it.isISOControl() } &&
        eventIds.size in (if (allowEmpty) 0 else 1)..128 &&
        eventIds.all(::validCollaborationEventId) &&
        eventIds.distinct().size == eventIds.size

fun validCollaborationReadReceiptIntent(value: CollaborationReadReceiptIntent) =
    validCollaborationMessageId(value.id) && validCollaborationEventId(value.eventId)

/** Detach bounded collections before another suspension can change the returned projection. */
fun snapshotCollaborationReadState(
    value: CollaborationReadState,
    eventIds: List<String>,
): CollaborationReadState? {
    if (value.readEventIds.size > 128 || value.pending.size > 128) return null
    val state =
        value.copy(readEventIds = value.readEventIds.toSet(), pending = value.pending.toList())
    val version = state.version
    if (version == null) {
        if (
            state.readEventIds.isNotEmpty() ||
                state.pending.isNotEmpty() ||
                state.acknowledged != null
        )
            return null
    } else if (
        !validCollaborationMessageId(version.generation) ||
            version.revision !in 0 until 9_007_199_254_740_991L
    )
        return null
    if (
        eventIds.size > 128 ||
            state.readEventIds.any { !validCollaborationEventId(it) || it !in eventIds } ||
            state.pending.any { !validCollaborationReadReceiptIntent(it) } ||
            state.pending.map { it.id }.distinct().size != state.pending.size ||
            (state.acknowledged != null &&
                !validCollaborationReadReceiptIntent(state.acknowledged)) ||
            state.pending.any { it.id == state.acknowledged?.id }
    )
        return null
    return state
}

fun validCollaborationReadFact(value: CollaborationHistoryEventFact?, eventId: String) =
    value != null &&
        value.eventId == eventId &&
        validCollaborationHistorySender(value.sender) &&
        (value.timestamp != null || value.redacted) &&
        (value.timestamp == null || value.timestamp in 0..9_007_199_254_740_991L)

fun ownsCollaborationReadConversation(
    access: CollaborationLocalAccess,
    conversationId: String,
    summary: CollaborationDirectSummary?,
): Boolean {
    val conversation = summary?.conversation ?: return false
    val peer =
        when (access.accountId) {
            conversation.firstAccountId -> conversation.secondAccountId
            conversation.secondAccountId -> conversation.firstAccountId
            else -> return false
        }
    return conversation.id == conversationId &&
        conversation.companyId == access.companyId &&
        validCollaborationMessageId(peer) &&
        peer != access.accountId &&
        summary.peer.accountId == peer
}
