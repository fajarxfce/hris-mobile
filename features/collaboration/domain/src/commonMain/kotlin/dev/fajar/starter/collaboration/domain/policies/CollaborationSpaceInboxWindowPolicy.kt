package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceInboxWindowRequest(
    access: CollaborationSpaceLocalAccess,
    anchor: CollaborationSpaceInboxWindowAnchor,
    limit: Int,
): Boolean =
    validCollaborationSpaceInboxPageRequest(access, null, limit) &&
        when (anchor) {
            CollaborationSpaceInboxWindowAnchor.Latest -> true
            is CollaborationSpaceInboxWindowAnchor.Conversation ->
                validCollaborationSpaceConversationId(anchor.id)
            is CollaborationSpaceInboxWindowAnchor.Older -> anchor.cursor.length in 1..4096
            is CollaborationSpaceInboxWindowAnchor.Newer -> anchor.cursor.length in 1..4096
        }

/** Validate one coherent bounded result; never infer another page from its size alone. */
fun validCollaborationSpaceInboxWindow(
    access: CollaborationSpaceLocalAccess,
    anchor: CollaborationSpaceInboxWindowAnchor,
    limit: Int,
    value: CollaborationSpaceInboxWindow,
): Boolean {
    if (!validCollaborationSpaceInboxWindowRequest(access, anchor, limit)) return false
    if (value == CollaborationSpaceInboxWindow.Changed)
        return anchor != CollaborationSpaceInboxWindowAnchor.Latest
    if (value == CollaborationSpaceInboxWindow.MissingAnchor)
        return anchor is CollaborationSpaceInboxWindowAnchor.Conversation
    val page = value as CollaborationSpaceInboxWindow.Items
    if (
        !validCollaborationSpaceInboxPage(
            access,
            null,
            limit,
            CollaborationSpaceInboxPage.Items(page.version, page.entries, null),
        )
    )
        return false
    if (page.version == null)
        return anchor == CollaborationSpaceInboxWindowAnchor.Latest &&
            page.previous == null &&
            page.next == null
    if (page.entries.isEmpty())
        return anchor !is CollaborationSpaceInboxWindowAnchor.Conversation &&
            page.previous == null &&
            page.next == null
    if (listOfNotNull(page.previous, page.next).any { it.length !in 1..4096 }) return false
    return when (anchor) {
        CollaborationSpaceInboxWindowAnchor.Latest -> page.previous == null
        is CollaborationSpaceInboxWindowAnchor.Conversation ->
            page.entries.any { it.conversationId == anchor.id }
        is CollaborationSpaceInboxWindowAnchor.Older -> page.next != anchor.cursor
        is CollaborationSpaceInboxWindowAnchor.Newer -> page.previous != anchor.cursor
    }
}
