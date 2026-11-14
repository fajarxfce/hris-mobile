package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceMessageOwner
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatMessageIdentity
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatMessageSource
import dev.fajar.starter.collaboration.presentation.thread.ChatMessageRow

/** Stable recipient-route coordinates; acquiring a real canonical target cannot change this key. */
data class RecipientChatMessageIdentity(
    val owner: CollaborationSpaceMessageOwner,
    val peerAccountId: String,
    val source: SpaceChatMessageSource,
) {
    val stableKey: String =
        (listOf("recipient", owner.audience, owner.accountId, owner.spaceId, peerAccountId) +
                when (source) {
                    is SpaceChatMessageSource.GroupCommand -> listOf("group", source.id)
                    is SpaceChatMessageSource.OriginalCommand ->
                        listOf(
                            "original",
                            source.owner.audience,
                            source.owner.accountId,
                            source.owner.companyId,
                            source.conversationId,
                            source.id,
                        )
                    is SpaceChatMessageSource.History ->
                        listOf("history", source.roomId, source.sender, source.eventId)
                })
            .joinToString("") { "${it.length}:$it" }

    override fun toString() = "RecipientChatMessageIdentity([redacted])"
}

/** Actual canonical action coordinates remain absent until the immutable association is known. */
data class RecipientChatMessageRow(
    val identity: RecipientChatMessageIdentity,
    val bubble: ChatMessageRow,
    val canonicalIdentity: SpaceChatMessageIdentity? = null,
    val position: Long = bubble.timestamp,
) {
    override fun toString() = "RecipientChatMessageRow([redacted])"
}
