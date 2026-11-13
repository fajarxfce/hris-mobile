package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*

internal const val SPACE_CHAT_SELECTION_LIMIT = 50
private const val CLIPBOARD_TEXT_LIMIT = 65_536

internal val SpaceChatThreadState.selecting
    get() = selection.isNotEmpty()

internal val SpaceChatThreadState.selectedRows
    get() = rows.filter { it.identity in selection }

internal val SpaceChatThreadState.canCopySelection: Boolean
    get() {
        val selected = selectedRows
        return restriction == null &&
            projectionFailure == null &&
            !leaving &&
            selected.isNotEmpty() &&
            selected.size == selection.size &&
            selected.all {
                it.bubble.kind == CollaborationMessageKind.TEXT && it.bubble.text != null
            } &&
            selected.sumOf { it.bubble.text.orEmpty().length + 2 } <= CLIPBOARD_TEXT_LIMIT
    }

internal val SpaceChatThreadState.canReplySelection: Boolean
    get() =
        restriction == null &&
            projectionFailure == null &&
            !leaving &&
            draftReady &&
            !legacyPending &&
            selectedRows.singleOrNull()?.bubble?.let {
                it.eventId != null &&
                    it.kind == CollaborationMessageKind.TEXT &&
                    it.text != null &&
                    it.delivery == CollaborationMessageDelivery.SENT
            } == true

/**
 * A single bounded UI action, retired on stop/replacement; never a durable export authorization.
 */
internal data class SpaceChatCopyRequest(
    val effect: SpaceChatThreadEffect.CopyMessages,
    val rows: List<SpaceChatMessageRow>,
    val generation: Long,
) {
    override fun toString() = "SpaceChatCopyRequest([redacted])"
}
