package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*

internal fun CollaborationParticipantDto.toParticipant(
    owner: CollaborationOwner
): CollaborationParticipant = toParticipant(owner.accountId)

private fun CollaborationParticipantDto.toParticipant(
    ownerAccount: String
): CollaborationParticipant {
    val account = collaborationId(accountId)
    require(account != ownerAccount)
    require(
        displayName.isNotBlank() &&
            displayName.length <= 200 &&
            displayName.none { it.code in 0..31 || it.code in 127..159 }
    )
    return CollaborationParticipant(account, displayName)
}

internal fun CollaborationDirectDto.toConversation(
    owner: CollaborationOwner
): CollaborationDirectConversation = toConversation(owner.accountId, owner.companyId)

private fun CollaborationDirectDto.toConversation(
    ownerAccount: String,
    company: String,
): CollaborationDirectConversation {
    collaborationId(id)
    require(collaborationId(companyId) == company)
    val first = collaborationId(firstAccountId)
    val second = collaborationId(secondAccountId)
    require(first < second && ownerAccount in listOf(first, second))
    require(collaborationId(createdBy) in listOf(first, second) && version == 0L)
    return CollaborationDirectConversation(
        id,
        companyId,
        first,
        second,
        createdBy,
        collaborationTime(createdAt),
        version,
    )
}

internal fun CollaborationDirectSummaryDto.toSummary(
    owner: CollaborationOwner
): CollaborationDirectSummary = toSummary(owner.accountId, owner.companyId)

internal fun CollaborationDirectSummaryDto.toSummary(
    ownerAccount: String,
    company: String,
): CollaborationDirectSummary {
    val direct = conversation.toConversation(ownerAccount, company)
    val counterpart = peer.toParticipant(ownerAccount)
    require(
        counterpart.accountId ==
            if (direct.firstAccountId == ownerAccount) direct.secondAccountId
            else direct.firstAccountId
    )
    return CollaborationDirectSummary(direct, counterpart)
}

internal fun CollaborationDirectRoomDto.toRoom(
    owner: CollaborationOwner,
    expected: String,
): CollaborationDirectRoom {
    require(collaborationId(conversationId) == expected)
    val state = CollaborationDirectRoomState.valueOf(state)
    require(version?.let { it in 0..999_999 } != false)
    creatorAccountId?.let(::collaborationId)
    val retryTime = retryAt?.let(::collaborationTime)
    require((retryTime != null) == retryable)
    if (state == CollaborationDirectRoomState.NOT_REQUESTED) {
        require(version == null && creatorAccountId == null && matrixRoomId == null && !retryable)
    } else {
        require(version != null && creatorAccountId != null)
        require((matrixRoomId != null) == (state == CollaborationDirectRoomState.READY))
        matrixRoomId?.let {
            require(
                it.length in 2..255 &&
                    it.startsWith("!") &&
                    it.none { c -> c.isWhitespace() || c.code < 33 || c.code in 127..159 }
            )
        }
        if (retryable)
            require(
                state in
                    setOf(
                        CollaborationDirectRoomState.FAILED,
                        CollaborationDirectRoomState.CANCELLED,
                    ) && creatorAccountId == owner.accountId && version < 999_999
            )
    }
    return CollaborationDirectRoom(
        conversationId,
        version,
        state,
        matrixRoomId,
        creatorAccountId,
        retryable,
        retryTime,
    )
}

/** UUID keyset pages must make strict progress, including their returned continuation. */
internal fun <T> conversationPage(
    items: List<T>,
    next: String?,
    after: String?,
    limit: Int,
    id: (T) -> String,
): CollaborationPage<T> {
    require(items.size <= limit)
    val ids = items.map(id)
    require(ids.zipWithNext().all { (a, b) -> a < b })
    require(after == null || ids.all { it > after })
    if (next != null) {
        collaborationId(next)
        require(items.size == limit && next == ids.lastOrNull())
    }
    return CollaborationPage(items, next)
}
