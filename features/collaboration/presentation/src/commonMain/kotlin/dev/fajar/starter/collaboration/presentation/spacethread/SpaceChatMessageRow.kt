package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.ChatMessageRow

/** Stable canonical ownership plus the actual command or provider namespace. */
data class SpaceChatMessageIdentity(
    val owner: CollaborationSpaceMessageOwner,
    val conversationId: String,
    val source: SpaceChatMessageSource,
) {
    /** Length framing also keeps provider identifiers containing separators unambiguous. */
    val stableKey: String =
        (listOf(owner.audience, owner.accountId, owner.spaceId, conversationId) +
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

    override fun toString() = "SpaceChatMessageIdentity([redacted])"
}

sealed interface SpaceChatMessageSource {
    data class GroupCommand(val id: String) : SpaceChatMessageSource {
        override fun toString() = "GroupCommand([redacted])"
    }

    data class OriginalCommand(
        val owner: CollaborationMessageOwner,
        val conversationId: String,
        val id: String,
    ) : SpaceChatMessageSource {
        override fun toString() = "OriginalCommand([redacted])"
    }

    data class History(val roomId: String, val sender: String, val eventId: String) :
        SpaceChatMessageSource {
        override fun toString() = "History([redacted])"
    }
}

/** Bubble is visual data only; selection and subsequent actions carry the typed identity. */
data class SpaceChatMessageRow(
    val identity: SpaceChatMessageIdentity,
    val bubble: ChatMessageRow,
    /**
     * Route-local placement, independent of the truthful displayed timestamp and delivery state.
     */
    val position: Long = bubble.timestamp,
) {
    override fun toString() = "SpaceChatMessageRow([redacted])"
}
