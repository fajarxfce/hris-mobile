package dev.fajar.starter.identity.data.sso

import dev.fajar.starter.identity.data.sso.datasources.DesktopBrowserAuthorizationSession
import dev.fajar.starter.identity.data.sso.dto.OidcRequestDto
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopAuthorizationTest {
    @Test
    fun cancellationClosesTheCallbackSocketAndReleasesTheResource() = runBlocking {
        withTimeout(5_000) {
            val port = freePort()
            val opened = CompletableDeferred<Unit>()
            var closed = 0
            val resource =
                DesktopBrowserAuthorizationSession(
                    "http://127.0.0.1:$port/oauth/callback",
                    openBrowser = { opened.complete(Unit) },
                    onClosed = { closed++ },
                )
            val operation = launch { resource.use { it.authorize(request(port)) } }
            opened.await()
            operation.cancelAndJoin()
            assertEquals(1, closed)
            resource.close()
            assertEquals(1, closed)
            assertPortReleased(port)
        }
    }

    @Test
    fun unsolicitedCallbackCannotFinishLoginAndAValidCallbackClosesTheSocket() = runBlocking {
        withTimeout(5_000) {
            val port = freePort()
            val opened = CompletableDeferred<Unit>()
            val resource =
                DesktopBrowserAuthorizationSession(
                    "http://127.0.0.1:$port/oauth/callback",
                    openBrowser = { opened.complete(Unit) },
                    onClosed = {},
                )
            val operation = async { resource.use { it.authorize(request(port)) } }
            try {
                opened.await()
                assertEquals(400, callback(port, "code=ignored&state=wrong"))
                assertFalse(operation.isCompleted)
                assertEquals(200, callback(port, "code=accepted&state=expected"))
                assertEquals("accepted", operation.await().code)
                assertPortReleased(port)
            } finally {
                operation.cancelAndJoin()
            }
        }
    }
}

private fun freePort(): Int =
    ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

private fun assertPortReleased(port: Int) {
    ServerSocket().use {
        it.reuseAddress = true
        it.bind(java.net.InetSocketAddress("127.0.0.1", port))
    }
}

private suspend fun callback(port: Int, query: String): Int =
    withContext(Dispatchers.IO) {
        val connection =
            URI("http://127.0.0.1:$port/oauth/callback?$query").toURL().openConnection()
                as HttpURLConnection
        try {
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

private fun request(port: Int) =
    OidcRequestDto(
        "https://idp.example",
        "https://idp.example/authorize",
        "https://idp.example/token",
        "https://idp.example/authorize?state=expected",
        "client",
        "http://127.0.0.1:$port/oauth/callback",
        "expected",
        "nonce",
        "v".repeat(43),
    )
