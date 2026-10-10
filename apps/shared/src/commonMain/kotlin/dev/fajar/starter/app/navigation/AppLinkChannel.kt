package dev.fajar.starter.app.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/** Platform-owned handoff to the active UI. The latest unopened external intent wins. */
class AppLinkChannel {
    private val channel = Channel<String>(Channel.CONFLATED)
    val links = channel.receiveAsFlow()

    fun receive(uri: String) {
        channel.trySend(uri)
    }

    fun close() = channel.close()
}
