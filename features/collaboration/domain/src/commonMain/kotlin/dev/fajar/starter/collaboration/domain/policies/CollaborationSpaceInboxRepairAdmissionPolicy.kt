package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceInboxRepairRequest(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.accountId) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        limit in 1..32 &&
        (after == null || after.length in 1..4096)

/**
 * A finite captured pass may restart explicitly; malformed progress never triggers another read.
 */
fun validCollaborationSpaceInboxRepairPage(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    limit: Int,
    value: CollaborationSpaceInboxRepairPage,
): Boolean {
    if (!validCollaborationSpaceInboxRepairRequest(access, after, limit)) return false
    if (value == CollaborationSpaceInboxRepairPage.Restart) return after != null
    val page = value as CollaborationSpaceInboxRepairPage.Items
    if (page.version == null) return after == null && page.references.isEmpty() && page.next == null
    return validCollaborationMessageId(page.version.generation) &&
        page.version.revision in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER &&
        page.version.lastOrdinal in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER &&
        page.references.size <= limit &&
        page.references.all {
            validCollaborationSpaceInboxRepairReference(it) &&
                validCollaborationSpaceMessageIntent(access, it.intent) &&
                it.insertedAtRevision <= page.version.revision
        } &&
        page.references.map { it.intent.id }.distinct().size == page.references.size &&
        page.references.zipWithNext().all { (a, b) ->
            a.insertedAtRevision <= b.insertedAtRevision
        } &&
        (page.next == null ||
            page.references.isNotEmpty() && page.next.length in 1..4096 && page.next != after)
}

fun validCollaborationSpaceInboxRepairProgressChange(
    change: CollaborationSpaceInboxRepairProgressChange
): Boolean =
    when (change) {
        is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized ->
            validCollaborationMessageId(change.demand)
        is CollaborationSpaceInboxRepairProgressChange.Request ->
            validCollaborationMessageId(change.demand)
        CollaborationSpaceInboxRepairProgressChange.Begin -> true
        is CollaborationSpaceInboxRepairProgressChange.Restart ->
            validCollaborationSpaceInboxRepairPass(change.expected) && change.expected.after != null
        is CollaborationSpaceInboxRepairProgressChange.Advance ->
            validCollaborationSpaceInboxRepairPass(change.expected) &&
                (change.next == null ||
                    change.next.length in 1..4096 && change.next != change.expected.after)
    }
