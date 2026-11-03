package dev.fajar.starter.app.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Platform-owned handoff to the active UI. The latest unopened external intent wins. */
class AppLinkChannel {
    private val channel = Channel<AppIncomingLink>(Channel.CONFLATED)
    val links = channel.receiveAsFlow()

    fun receive(uri: String) {
        channel.trySend(AppIncomingLink.ExternalUri(uri))
    }

    fun receive(input: dev.fajar.starter.notifications.data.dto.ActionNotificationInput) {
        channel.trySend(AppIncomingLink.Notification(input))
    }

    fun close() = channel.close()
}
