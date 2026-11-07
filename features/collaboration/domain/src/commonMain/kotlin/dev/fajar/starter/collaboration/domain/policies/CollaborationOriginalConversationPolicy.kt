package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Instant

fun validCollaborationOriginalConversationOwner(owner: CollaborationSpaceOwner): Boolean =
    originalConversationId(owner.spaceId) &&
        originalConversationId(owner.parent.accountId) &&
        originalConversationId(owner.parent.nativeSessionId)

fun validCollaborationOriginalConversationPosition(
    value: CollaborationOriginalConversationPosition
): Boolean = originalConversationId(value.companyId) && originalConversationId(value.conversationId)

fun validCollaborationOriginalConversationQuery(
    owner: CollaborationSpaceOwner,
    after: CollaborationOriginalConversationPosition?,
    limit: Int,
): Boolean =
    validCollaborationOriginalConversationOwner(owner) &&
        limit in 1..50 &&
        (after == null || validCollaborationOriginalConversationPosition(after))

fun validCollaborationSpaceOriginalConversation(
    value: CollaborationSpaceOriginalConversation,
    owner: CollaborationSpaceOwner,
    expected: CollaborationOriginalConversationPosition? = null,
): Boolean {
    val c = value.conversation
    val pair = listOf(c.firstAccountId, c.secondAccountId)
    if (
        value.owner != owner ||
            !validCollaborationOriginalConversationOwner(owner) ||
            !validCollaborationOriginalConversationPosition(c.position) ||
            (expected != null && c.position != expected) ||
            pair.any { !originalConversationId(it) } ||
            c.firstAccountId >= c.secondAccountId ||
            owner.parent.accountId !in pair ||
            c.createdBy !in pair ||
            c.version != 0L ||
            !originalConversationTime(c.createdAt)
    )
        return false
    val room = value.room ?: return true
    return validCollaborationSpaceServerName(room.serverName) &&
        room.roomId.length in 2..255 &&
        room.roomId.startsWith('!') &&
        room.roomId.none { it.isWhitespace() || it.code in 0..31 || it.code in 127..159 } &&
        room.createdBy in pair &&
        originalConversationTime(room.boundAt) &&
        room.boundAt >= c.createdAt
}

fun validCollaborationSpaceOriginalConversationPage(
    page: CollaborationSpaceOriginalConversationPage,
    owner: CollaborationSpaceOwner,
    after: CollaborationOriginalConversationPosition?,
    limit: Int,
): Boolean =
    validCollaborationOriginalConversationQuery(owner, after, limit) &&
        page.owner == owner &&
        page.items.size <= limit &&
        page.items.all {
            validCollaborationSpaceOriginalConversation(it, owner) &&
                (after == null || originalConversationOrder(after, it.conversation.position))
        } &&
        page.items.zipWithNext().all { (a, b) ->
            originalConversationOrder(a.conversation.position, b.conversation.position)
        } &&
        (page.next == null ||
            (page.items.size == limit &&
                page.next == page.items.lastOrNull()?.conversation?.position))

private fun originalConversationTime(value: Instant): Boolean =
    value.epochSeconds in -62_135_596_800L..253_402_300_799L &&
        value.nanosecondsOfSecond % 1000 == 0

private fun originalConversationId(value: String): Boolean =
    validCollaborationMessageId(value) && value != "00000000-0000-0000-0000-000000000000"

private fun originalConversationOrder(
    a: CollaborationOriginalConversationPosition,
    b: CollaborationOriginalConversationPosition,
): Boolean =
    a.companyId < b.companyId || (a.companyId == b.companyId && a.conversationId < b.conversationId)
