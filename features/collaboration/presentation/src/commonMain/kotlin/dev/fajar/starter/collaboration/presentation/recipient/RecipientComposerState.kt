package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.common.result.Failure

data class RecipientComposerState(
    val visible: Boolean = false,
    val editor: RecipientComposerEditingState = RecipientComposerEditingState(),
    val readFailure: Failure? = null,
    val restriction: Failure? = null,
) {
    override fun toString() = "RecipientComposerState([redacted])"
}

sealed interface RecipientComposerEvent {
    data object Entered : RecipientComposerEvent

    data object Stopped : RecipientComposerEvent

    data class TextChanged(val text: String) : RecipientComposerEvent {
        override fun toString() = "TextChanged([redacted])"
    }

    data object SendRequested : RecipientComposerEvent

    data object RetryRequested : RecipientComposerEvent
}
