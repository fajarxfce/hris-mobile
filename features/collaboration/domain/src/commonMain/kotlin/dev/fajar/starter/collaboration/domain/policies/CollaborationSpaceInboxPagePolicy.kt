package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceInboxPageRequest(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        limit in 1..50 &&
        (after == null || after.length in 1..4096)

/** A recency page keeps its repository order; only a real continuation permits another read. */
fun validCollaborationSpaceInboxPage(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
    value: CollaborationSpaceInboxPage,
): Boolean {
    if (!validCollaborationSpaceInboxPageRequest(access, after, limit)) return false
    if (value == CollaborationSpaceInboxPage.Restart) return after != null
    val page = value as CollaborationSpaceInboxPage.Items
    val version = page.version
    if (version == null) return after == null && page.entries.isEmpty() && page.next == null
    if (
        !collaborationSpaceInboxHistoryDestinationCovers(version, version) ||
            page.entries.size > limit ||
            page.entries.map { it.conversationId }.distinct().size != page.entries.size ||
            (page.next != null &&
                (page.next.length !in 1..4096 || page.next == after || page.entries.size != limit))
    )
        return false
    if (page.entries.any { !validCollaborationSpaceInboxPageEntry(it, version) }) return false
    return page.entries.zipWithNext().all { (first, second) ->
        compareCollaborationSpaceInboxPositions(first.position, second.position) >= 0
    }
}

private fun validCollaborationSpaceInboxPageEntry(
    entry: CollaborationSpaceInboxEntry,
    version: CollaborationSpaceInboxVersion,
): Boolean {
    if (
        !validCollaborationMessageId(entry.conversationId) ||
            entry.createdAt !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            !validCollaborationSpaceInboxPosition(entry.position) ||
            entry.position.ordinal > version.lastOrdinal
    )
        return false
    val draft = entry.draft
    if (
        draft != null &&
            (!validCollaborationSpaceInboxDraft(draft) ||
                draft.value.conversationId != entry.conversationId ||
                draft.position.ordinal > version.lastOrdinal)
    )
        return false
    val newest = entry.newest
    if (
        newest != null &&
            (!validCollaborationSpaceInboxActivity(newest, version.revision) ||
                newest.preview == null ||
                newest.position.ordinal > version.lastOrdinal)
    )
        return false
    return entry ==
        collaborationSpaceInboxEntry(entry.conversationId, entry.createdAt, draft, newest)
}
