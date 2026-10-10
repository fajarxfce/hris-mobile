package dev.fajar.starter.identity.data.sso.datasources

import com.sun.net.httpserver.HttpServer
import dev.fajar.starter.identity.data.sso.dto.*
import dev.fajar.starter.identity.data.sso.mappers.decodeOidcCallback
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import kotlinx.coroutines.*

/** Bound exclusively to IPv4 loopback; socket and executor exist only during authorization. */
internal class DesktopBrowserAuthorizationSession(
    private val redirectUri: String,
    private val openBrowser: suspend (URI) -> Unit = ::openDesktopAuthorizationBrowser,
    private val onClosed: () -> Unit,
) : BrowserAuthorizationSession {
    private var closed = false
    private var started = false
    private val response = CompletableDeferred<OidcResponseDto>()

    override suspend fun authorize(request: OidcRequestDto): OidcResponseDto {
        check(!closed && !started) { "The authorization resource cannot be reused." }
        started = true
        return withContext(Dispatchers.IO) {
            val redirect = URI(redirectUri)
            require(
                redirect.scheme == "http" &&
                    redirect.host == "127.0.0.1" &&
                    redirect.port in 1024..65535
            )
            val server =
                HttpServer.create(
                    InetSocketAddress(
                        InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                        redirect.port,
                    ),
                    0,
                )
            val executor =
                Executors.newSingleThreadExecutor {
                    Thread(it, "oidc-callback").apply { isDaemon = true }
                }
            try {
                server.executor = executor
                server.createContext(redirect.path) { exchange ->
                    try {
                        if (
                            exchange.requestMethod != "GET" ||
                                exchange.requestURI.path != redirect.path ||
                                exchange.requestURI.toString().length > 8192 ||
                                exchange.requestHeaders.getFirst("Host") != redirect.authority
                        ) {
                            exchange.sendResponseHeaders(400, -1)
                        } else {
                            val callback =
                                decodeOidcCallback(
                                    "http://${redirect.authority}${exchange.requestURI}",
                                    redirectUri,
                                )
                            if (callback.state != request.state)
                                exchange.sendResponseHeaders(400, -1)
                            else {
                                val text = "Sign-in complete. Return to the app.".toByteArray()
                                exchange.responseHeaders.set(
                                    "Content-Type",
                                    "text/plain; charset=utf-8",
                                )
                                exchange.responseHeaders.set("Cache-Control", "no-store")
                                exchange.sendResponseHeaders(200, text.size.toLong())
                                exchange.responseBody.use { it.write(text) }
                                response.complete(callback)
                            }
                        }
                    } catch (error: Exception) {
                        response.completeExceptionally(error)
                    } finally {
                        exchange.close()
                    }
                }
                server.start()
                openBrowser(URI(request.authorizationUrl))
                response.await()
            } finally {
                server.stop(0)
                executor.shutdownNow()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        response.cancel()
        onClosed()
    }
}
