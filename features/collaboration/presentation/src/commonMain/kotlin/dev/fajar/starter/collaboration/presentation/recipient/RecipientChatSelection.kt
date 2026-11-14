package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatMessageSource
import dev.fajar.starter.collaboration.presentation.thread.ChatMessageOwnership

internal const val RECIPIENT_CHAT_SELECTION_LIMIT = 50
private const val CLIPBOARD_TEXT_LIMIT = 65_536

internal val RecipientChatThreadState.selecting
    get() = selection.isNotEmpty()

internal val RecipientChatThreadState.selectedRows
    get() = rows.filter { it.identity in selection }

internal val RecipientChatThreadState.canCopySelection: Boolean
    get() {
        val selected = selectedRows
        return visible &&
            !leaving &&
            restriction == null &&
            projectionFailure == null &&
            selected.isNotEmpty() &&
            selected.size == selection.size &&
            selected.size <= RECIPIENT_CHAT_SELECTION_LIMIT &&
            selected.all {
                it.bubble.kind == CollaborationMessageKind.TEXT && it.bubble.text != null
            } &&
            selected.sumOf { it.bubble.text.orEmpty().length + 2 } <= CLIPBOARD_TEXT_LIMIT
    }

/** One bounded UI request. Current local admission is checked by the context use case. */
internal data class RecipientChatCopyRequest(
    val effect: RecipientChatThreadEffect.CopyMessages,
    val rows: List<RecipientChatMessageRow>,
    val generation: Long,
) {
    override fun toString() = "RecipientChatCopyRequest([redacted])"
}

/** Match the displayed row to the real context; never substitute a peer ID for a conversation. */
internal fun recipientChatCopyMatchesContext(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    destination: CollaborationSpaceConversationMetadata?,
    row: RecipientChatMessageRow,
    messages: RecipientChatPresentation,
): Boolean {
    val owner = access.messageOwner()
    if (
        row.identity.owner != owner ||
            row.identity.peerAccountId != peerAccountId ||
            row.bubble.key != row.identity.stableKey
    )
        return false
    val canonical = row.canonicalIdentity
    if (canonical != null)
        return destination != null &&
            canonical.owner == owner &&
            canonical.conversationId == destination.id &&
            canonical.source == row.identity.source
    // Before association only the exact private captured Send is available. Provider rows and
    // another company's command cannot acquire clipboard authority from an absent destination.
    val source = row.identity.source as? SpaceChatMessageSource.GroupCommand ?: return false
    return row.bubble.ownership == ChatMessageOwnership.SELF &&
        row.bubble.eventId == null &&
        messages.pending.any {
            val original = it.original
            original.id == source.id &&
                original.original.peerAccountId == peerAccountId &&
                original.original.text == row.bubble.text &&
                original.submittedAt.toEpochMilliseconds() == row.bubble.timestamp
        }
}
