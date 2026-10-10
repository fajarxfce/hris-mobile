@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.identity.data.sso.datasources

import dev.fajar.starter.identity.data.sso.boundary.BrowserAuthorizationCancelled
import dev.fajar.starter.identity.data.sso.dto.*
import dev.fajar.starter.identity.data.sso.mappers.decodeOidcCallback
import kotlin.js.js
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import org.w3c.dom.MessageEvent
import org.w3c.dom.Window
import org.w3c.dom.events.Event

/** A single owner removes the listener, interval and popup on every completion path. */
internal class WebBrowserAuthorizationSession(
    private val popup: Window,
    private val origin: String,
    private val redirectUri: String,
    private val onClosed: () -> Unit,
) : BrowserAuthorizationSession {
    private var closed = false
    private var started = false
    private var closeMonitor: Int? = null
    private val response = CompletableDeferred<OidcResponseDto>()
    private val messages: (Event) -> Unit = { event ->
        if (event is MessageEvent && event.origin == origin && event.source == popup) {
            val callback = readAuthorizationCallback(event)
            if (callback != null && callback.length <= 8192) {
                try {
                    response.complete(decodeOidcCallback(callback, redirectUri))
                } catch (error: Exception) {
                    response.completeExceptionally(error)
                }
            }
        }
    }

    override suspend fun authorize(request: OidcRequestDto): OidcResponseDto {
        check(!closed && !started) { "The authorization resource cannot be reused." }
        started = true
        window.addEventListener("message", messages)
        closeMonitor =
            window.setInterval(
                {
                    if (popup.closed)
                        response.completeExceptionally(BrowserAuthorizationCancelled())
                    null
                },
                200,
            )
        popup.location.href = request.authorizationUrl
        return response.await()
    }

    override fun close() {
        if (closed) return
        closed = true
        window.removeEventListener("message", messages)
        closeMonitor?.let(window::clearInterval)
        closeMonitor = null
        response.cancel()
        try {
            popup.close()
        } finally {
            onClosed()
        }
    }
}

/** Only the typed callback envelope is accepted; unrelated postMessage traffic is ignored. */
private fun readAuthorizationCallback(event: MessageEvent): String? =
    js(
        "event.data && event.data.type === 'fluent-oidc' && typeof event.data.url === 'string' ? event.data.url : null"
    )
