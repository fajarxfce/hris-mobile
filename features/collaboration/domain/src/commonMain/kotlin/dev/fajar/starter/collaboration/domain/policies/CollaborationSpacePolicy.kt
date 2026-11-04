package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Duration.Companion.seconds

fun validCollaborationSpaceObservation(
    value: CollaborationSpaceObservation,
    parent: CollaborationNativeParent,
): Boolean =
    value.owner.parent == parent &&
        validCollaborationMessageId(value.owner.spaceId) &&
        value.nativeSessionVersion >= 0 &&
        value.credentialVersion >= 0 &&
        value.admittedAt.toEpochMilliseconds() >= 0 &&
        (value.expiresAt - value.admittedAt).let { it > 0.seconds && it <= 60.seconds }

fun validCollaborationSpaceContactRequest(
    owner: CollaborationSpaceOwner,
    query: String,
    after: String?,
    limit: Int,
): Boolean =
    validCollaborationMessageId(owner.spaceId) &&
        query.length <= 80 &&
        query.none { it.code in 0..31 || it.code in 127..159 } &&
        (after == null || validCollaborationMessageId(after)) &&
        limit in 1..50

fun validCollaborationSpaceContactPage(
    page: CollaborationSpaceContactPage,
    owner: CollaborationSpaceOwner,
    after: String?,
    limit: Int,
): Boolean =
    limit in 1..50 &&
        page.owner == owner &&
        page.items.size <= limit &&
        page.items.all { contact ->
            validCollaborationSpaceContact(contact, owner.parent.accountId) &&
                (after == null || contact.accountId > after)
        } &&
        page.items.zipWithNext().all { (a, b) -> a.accountId < b.accountId } &&
        (page.nextCursor == null ||
            (page.items.size == limit && page.nextCursor == page.items.last().accountId))

/** Public labels only; shared by remote pages and the account-owned encrypted directory. */
fun validCollaborationSpaceContact(
    contact: CollaborationSpaceContact,
    excludedAccountId: String,
): Boolean =
    validCollaborationMessageId(contact.accountId) &&
        contact.accountId != excludedAccountId &&
        contact.displayName.isNotBlank() &&
        contact.displayName.length <= 200 &&
        contact.companies.size in 1..8 &&
        (!contact.hasMoreCompanies || contact.companies.size == 8) &&
        contact.companies.all {
            validCollaborationMessageId(it.id) && it.name.isNotBlank() && it.name.length <= 200
        } &&
        contact.companies.map { it.id }.distinct().size == contact.companies.size
