package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.ByteReadChannel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadOnlyPostAuthenticationTest {
    @Test
    fun explicitReadOnlyPostRetainsBodyAndScopeWithAtMostOneAuthenticationReplay() = runTest {
        for (lastStatus in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
            val bodies = mutableListOf<String>()
            val tokens = mutableListOf<String?>()
            val rejected = mutableListOf<String?>()
            val client =
                createHttpClient(
                    MockEngine {
                        bodies += it.body.toByteArray().decodeToString()
                        tokens += it.headers[HttpHeaders.Authorization]
                        assertNull(it.headers["Idempotency-Key"])
                        respond(
                            "ok",
                            if (bodies.size == 1) HttpStatusCode.Unauthorized else lastStatus,
                        )
                    },
                    HttpClientSettings("https://api.example.test/"),
                ) {
                    install(SessionAuthentication) {
                        origin = Url("https://api.example.test/")
                        acquire = { session, token ->
                            assertEquals("origin-session", session)
                            rejected += token
                            AppResult.Success(if (token == null) "initial" else "refreshed")
                        }
                    }
                }
            try {
                val result = safeApiCall {
                    client
                        .post("observation") {
                            forSession("origin-session", AuthenticationReplay.ReadOnlyPost)
                            setBody(TextContent("private-query", ContentType.Application.Json))
                        }
                        .bodyAsText()
                }
                if (lastStatus == HttpStatusCode.OK)
                    assertEquals("ok", assertIs<AppResult.Success<String>>(result).value)
                else
                    assertEquals(
                        FailureKind.Unauthorized,
                        assertIs<AppResult.Failed>(result).failure.kind,
                    )
                assertEquals(listOf("private-query", "private-query"), bodies)
                assertEquals<List<String?>>(listOf("Bearer initial", "Bearer refreshed"), tokens)
                assertEquals<List<String?>>(listOf(null, "initial"), rejected)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun readOnlyMarkerDoesNotPermitStreamingBodyOrOtherUnsafeMethodReplay() = runTest {
        for (streaming in listOf(true, false)) {
            var calls = 0
            val client =
                createHttpClient(
                    MockEngine {
                        calls++
                        respond("", HttpStatusCode.Unauthorized)
                    },
                    HttpClientSettings("https://api.example.test/"),
                ) {
                    install(SessionAuthentication) {
                        origin = Url("https://api.example.test/")
                        acquire = { _, rejected ->
                            AppResult.Success(if (rejected == null) "initial" else "refreshed")
                        }
                    }
                }
            try {
                assertIs<AppResult.Failed>(
                    safeApiCall {
                        client.request("observation") {
                            method = if (streaming) HttpMethod.Post else HttpMethod.Patch
                            forSession("origin-session", AuthenticationReplay.ReadOnlyPost)
                            if (streaming)
                                setBody(
                                    object : OutgoingContent.ReadChannelContent() {
                                        override fun readFrom() =
                                            ByteReadChannel("stream".encodeToByteArray())
                                    }
                                )
                            else setBody("repeatable")
                        }
                    }
                )
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun cancellationDuringReadOnlyPostRefreshNeverResends() = runTest {
        val refreshing = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var calls = 0
        val client =
            createHttpClient(
                MockEngine {
                    calls++
                    respond("", HttpStatusCode.Unauthorized)
                },
                HttpClientSettings("https://api.example.test/"),
            ) {
                install(SessionAuthentication) {
                    origin = Url("https://api.example.test/")
                    acquire = { _, rejected ->
                        if (rejected == null) AppResult.Success("initial")
                        else {
                            refreshing.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                    }
                }
            }
        try {
            val pending = async {
                client.post("observation") {
                    forSession("origin-session", AuthenticationReplay.ReadOnlyPost)
                    setBody("query")
                }
            }
            refreshing.await()
            pending.cancelAndJoin()
            stopped.await()
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }
}
