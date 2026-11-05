package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*

internal fun sameChatMessage(a: ChatMessageRow, b: ChatMessageRow): Boolean {
    if (a.key == b.key) return true
    val first = a.reference ?: return false
    val second = b.reference ?: return false
    return first.sender == second.sender &&
        (first.eventId == second.eventId ||
            (first.logicalId != null && first.logicalId == second.logicalId))
}

internal val ChatThreadState.canCopySelection: Boolean
    get() =
        selectedMessages.isNotEmpty() &&
            selectedMessages.all { it.kind == CollaborationMessageKind.TEXT && it.text != null }

internal val ChatThreadState.canDeleteSelection: Boolean
    get() = selectedMessages.isNotEmpty() && selectedMessages.all { it.reference != null }

internal data class ChatVisibilityProjection(
    val access: CollaborationLocalAccess,
    val roomId: String?,
    val rows: List<ChatMessageRow>,
    val loading: Boolean,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val browsingHistory: Boolean,
) {
    override fun toString() = "ChatVisibilityProjection([redacted])"
}

internal data class ChatDeletionSelection(
    val access: CollaborationLocalAccess,
    val roomId: String,
    val messages: List<CollaborationMessageReference>,
) {
    override fun toString() = "ChatDeletionSelection([redacted])"
}
