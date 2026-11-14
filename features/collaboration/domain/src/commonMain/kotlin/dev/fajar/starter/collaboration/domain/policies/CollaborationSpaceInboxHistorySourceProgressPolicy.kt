package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Validate retained coordinates independently of current credentials and provider availability. */
fun validCollaborationSpaceInboxHistorySourceCheckpoint(
    access: CollaborationSpaceLocalAccess,
    value: CollaborationSpaceInboxHistorySourceCheckpoint,
): Boolean {
    if (
        !validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        ) ||
            !validCollaborationSpaceConversationId(access.spaceId) ||
            !validCollaborationMessageId(value.version.generation) ||
            value.version.revision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
    )
        return false
    val progress = value.progress
    val owner = progress.owner
    if (
        owner.audience != access.audience ||
            owner.accountId != access.accountId ||
            !validCollaborationMessageId(owner.organizationId) ||
            !validCollaborationMessageId(owner.conversationId) ||
            !validCollaborationSpaceIncomingRoomId(owner.roomId) ||
            !validCollaborationMessageId(progress.conversationId) ||
            !validCollaborationMessageId(progress.passId) ||
            (progress.destinationGeneration != null &&
                !validCollaborationMessageId(progress.destinationGeneration))
    )
        return false
    if (
        owner.kind == CollaborationHistoryChangeOwnerKind.SPACE &&
            (owner.organizationId != access.spaceId ||
                owner.conversationId != progress.conversationId)
    )
        return false
    val cursor = progress.historyCursor
    if (
        !validCollaborationHistoryChangeCursor(cursor) ||
            (cursor != null && collaborationHistoryChangeCursorOwner(cursor) != owner) ||
            (progress.historyComplete && cursor is CollaborationHistoryChangeCursor.Within)
    )
        return false
    val rawUpper =
        when (cursor) {
            null -> 0L
            is CollaborationHistoryChangeCursor.After -> cursor.revision
            is CollaborationHistoryChangeCursor.Within -> cursor.window.throughRevision
        }
    if (rawUpper > COLLABORATION_SPACE_INBOX_MAX_ORDER) return false
    val pending = progress.pendingWindow ?: return true
    return pending.generation == value.version.generation &&
        pending.throughRevision in 0..value.version.revision &&
        (pending.after == null || pending.after.length in 1..4096)
}

/** Later activity in the same destination is compatible; replacement or regression is not. */
fun collaborationSpaceInboxHistoryDestinationCovers(
    receipt: CollaborationSpaceInboxVersion?,
    current: CollaborationSpaceInboxVersion?,
): Boolean {
    if (receipt == null || current == null) return receipt == current
    return listOf(receipt, current).all {
        validCollaborationMessageId(it.generation) &&
            it.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER &&
            it.lastOrdinal in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER
    } &&
        current.generation == receipt.generation &&
        current.revision >= receipt.revision &&
        current.lastOrdinal >= receipt.lastOrdinal &&
        (current.revision != receipt.revision || current.lastOrdinal == receipt.lastOrdinal)
}

/**
 * Structural bounds for the storage proposal; source publication/admission remains in use cases.
 */
fun validCollaborationSpaceInboxHistorySourceMutation(
    access: CollaborationSpaceLocalAccess,
    value: CollaborationSpaceInboxHistorySourceMutation,
): Boolean {
    val ids = value.retainedEventIds + value.unresolvedEventIds
    val expected = value.expected
    val replacement = value.replacement
    if (ids.size > 16 || ids.distinct() != ids || ids.any { !validCollaborationEventId(it) })
        return false
    if (
        !collaborationSpaceInboxHistoryDestinationCovers(value.destination, value.destination) ||
            replacement.destinationGeneration != value.destination?.generation
    )
        return false
    if (
        expected != null &&
            (!validCollaborationSpaceInboxHistorySourceCheckpoint(access, expected) ||
                expected.progress.conversationId != replacement.conversationId ||
                expected.progress.owner != replacement.owner)
    )
        return false
    val pending = replacement.pendingWindow ?: return true
    return expected != null &&
        pending.generation == expected.version.generation &&
        pending.throughRevision in 0..expected.version.revision
}
