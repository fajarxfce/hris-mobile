package dev.fajar.starter.collaboration.presentation.spaceinbox

import dev.fajar.starter.common.result.Failure

data class SpaceChatInboxState(
    val visible: Boolean = false,
    val names: Map<String, String?> = emptyMap(),
    val restriction: Failure? = null,
) {
    override fun toString() = "SpaceChatInboxState([redacted])"
}

sealed interface SpaceChatInboxEvent {
    data object Entered : SpaceChatInboxEvent

    data object Stopped : SpaceChatInboxEvent

    class ViewportChanged(visible: List<String>, loaded: List<String>) : SpaceChatInboxEvent {
        val visible = visible.take(50).toList()
        val loaded = loaded.take(200).toList()
    }

    data class ConversationSelected(val id: String) : SpaceChatInboxEvent
}

sealed interface SpaceChatInboxEffect {
    data class OpenConversation(val id: String) : SpaceChatInboxEffect
}
