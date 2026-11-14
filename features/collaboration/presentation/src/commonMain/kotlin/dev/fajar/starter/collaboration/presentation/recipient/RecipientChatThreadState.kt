package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/**
 * Independent local facts and one route-owned editor; no transport state is exposed as UI state.
 */
data class RecipientChatThreadState(
    val visible: Boolean = false,
    val loading: Boolean = true,
    val editor: RecipientComposerEditingState = RecipientComposerEditingState(),
    val source: CollaborationSpaceRecipientProjection? = null,
    val destination: CollaborationSpaceConversationMetadata? = null,
    val thread: CollaborationSpaceThreadSnapshot? = null,
    val messages: RecipientChatPresentation = RecipientChatPresentation(),
    val rows: List<RecipientChatMessageRow> = emptyList(),
    val sourceFailure: Failure? = null,
    val destinationFailure: Failure? = null,
    val historyFailure: Failure? = null,
    val progressFailure: Failure? = null,
    val projectionFailure: Failure? = null,
    val restriction: Failure? = null,
) {
    val canCompose
        get() = visible && editor.ready && restriction == null

    val canSend
        get() =
            canCompose &&
                projectionFailure == null &&
                editor.text.isNotBlank() &&
                messages.pending.size < 128 &&
                editor.submissions.count { !it.durable } < 16

    override fun toString() = "RecipientChatThreadState([redacted])"
}

sealed interface RecipientChatThreadEvent {
    data object Prepared : RecipientChatThreadEvent

    data object Entered : RecipientChatThreadEvent

    data object Stopped : RecipientChatThreadEvent

    data class TextChanged(val text: String) : RecipientChatThreadEvent {
        override fun toString() = "TextChanged([redacted])"
    }

    data object SendRequested : RecipientChatThreadEvent

    data object RetryRequested : RecipientChatThreadEvent
}
