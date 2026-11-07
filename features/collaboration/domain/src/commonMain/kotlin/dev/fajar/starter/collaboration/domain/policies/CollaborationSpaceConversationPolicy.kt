package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationSpaceConversationQuery(
    owner: CollaborationSpaceOwner,
    after: String?,
    limit: Int,
): Boolean =
    listOf(owner.spaceId, owner.parent.accountId, owner.parent.nativeSessionId).all {
        validCollaborationSpaceConversationId(it)
    } && limit in 1..50 && (after == null || validCollaborationSpaceConversationId(after))

fun validCollaborationSpaceConversation(
    value: CollaborationSpaceConversation,
    owner: CollaborationSpaceOwner = value.owner,
    expectedId: String? = null,
): Boolean =
    value.owner == owner &&
        validCollaborationSpaceConversationQuery(owner, null, 1) &&
        listOf(value.id, value.firstAccountId, value.secondAccountId).all {
            validCollaborationSpaceConversationId(it)
        } &&
        (expectedId == null || value.id == expectedId) &&
        value.firstAccountId < value.secondAccountId &&
        owner.parent.accountId in listOf(value.firstAccountId, value.secondAccountId) &&
        value.createdBy in listOf(value.firstAccountId, value.secondAccountId) &&
        value.version == 0L &&
        value.createdAt.epochSeconds in -62_135_596_800L..253_402_300_799L &&
        value.createdAt.nanosecondsOfSecond % 1000 == 0

fun validCollaborationSpaceConversationPage(
    page: CollaborationSpaceConversationPage,
    owner: CollaborationSpaceOwner,
    after: String?,
    limit: Int,
): Boolean =
    validCollaborationSpaceConversationQuery(owner, after, limit) &&
        page.owner == owner &&
        page.items.size <= limit &&
        page.items.all {
            validCollaborationSpaceConversation(it, owner) && (after == null || it.id > after)
        } &&
        page.items.zipWithNext().all { (a, b) -> a.id < b.id } &&
        (page.next == null ||
            (page.items.size == limit && page.next == page.items.lastOrNull()?.id))

fun validCollaborationSpaceConversationId(value: String): Boolean =
    validCollaborationMessageId(value) && value != "00000000-0000-0000-0000-000000000000"
