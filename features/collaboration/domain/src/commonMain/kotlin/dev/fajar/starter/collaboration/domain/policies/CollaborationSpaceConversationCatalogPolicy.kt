package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceCatalogRequest(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    rawLimit: Int,
): Boolean =
    validCollaborationSpaceAccountAccess(
        CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
    ) &&
        validCollaborationSpaceConversationId(access.spaceId) &&
        rawLimit in 1..50 &&
        (after == null || after.length in 1..4096)

fun validCollaborationSpaceCatalogVersion(
    value: CollaborationSpaceConversationCatalogVersion
): Boolean =
    validCollaborationSpaceConversationId(value.generation) &&
        value.revision in 0..9_007_199_254_740_991L

/** The inventory cursor controls progress; an empty pair-only page is not exhaustion. */
fun validCollaborationSpaceCatalogRead(
    access: CollaborationSpaceLocalAccess,
    after: String?,
    rawLimit: Int,
    value: CollaborationSpaceConversationCatalogRead,
): Boolean {
    if (!validCollaborationSpaceCatalogRequest(access, after, rawLimit)) return false
    if (value == CollaborationSpaceConversationCatalogRead.Restart) return true
    val page = value as CollaborationSpaceConversationCatalogRead.Page
    if (page.version == null)
        return after == null && page.items.isEmpty() && page.next == null && !page.changed
    return validCollaborationSpaceCatalogVersion(page.version) &&
        page.items.size <= rawLimit &&
        page.items.all { validCollaborationSpaceConversationMetadata(access, it) } &&
        page.items.map { it.id }.distinct().size == page.items.size &&
        page.items.map { it.firstAccountId to it.secondAccountId }.distinct().size ==
            page.items.size &&
        (page.next == null || page.next.length in 1..4096 && page.next != after)
}
