package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class JsonRequestTest {
    @Test
    fun jsonAndEmptyCommandsAreReadThroughTheBoundedTransport() = runTest {
        val client =
            createHttpClient(
                MockEngine {
                    if (it.url.encodedPath == "/empty") respond("", HttpStatusCode.NoContent)
                    else
                        respond(
                            """{"name":"Example"}""",
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType,
                                    "application/json; charset=utf-8",
                                ),
                        )
                },
                "https://example.invalid/",
            )
        try {
            assertEquals(
                mapOf("name" to "Example"),
                client.requestJson<Map<String, String>>("data"),
            )
            assertEquals(Unit, client.requestJson<Unit>("empty"))
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedOrUnboundedSuccessResponsesFailInsideTheRepositoryBoundary() = runTest {
        val payloads =
            listOf(
                "x".repeat(129).encodeToByteArray() to "application/json",
                "[".repeat(65).plus("0").plus("]".repeat(65)).encodeToByteArray() to
                    "application/json",
                byteArrayOf(0xc3.toByte(), 0x28) to "application/json",
                "{}".encodeToByteArray() to "text/html",
            )
        for ((index, payload) in payloads.withIndex()) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond(
                            ByteReadChannel(payload.first),
                            headers = headersOf(HttpHeaders.ContentType, payload.second),
                        )
                    },
                    "https://example.invalid/",
                )
            try {
                val result = safeApiCall {
                    client.requestJson<Map<String, String>>(
                        "data",
                        maximumBytes = if (index == 0) 128 else 1024,
                    )
                }
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(result).failure.kind,
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun anOversizedStreamIsCancelledWithoutDrainingTheProducer() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val stopped = CompletableDeferred<Unit>()
        var written = 0
        val producer =
            backgroundScope.launch(Dispatchers.Default) {
                try {
                    repeat(1_024) {
                        channel.writeFully(ByteArray(4_096))
                        written++
                    }
                    channel.close()
                } catch (_: IOException) {
                    // The client owns and cancels the response stream at its configured limit.
                } finally {
                    stopped.complete(Unit)
                }
            }
        val client =
            createHttpClient(
                MockEngine {
                    respond(
                        channel,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://example.invalid/",
            )
        try {
            assertIs<AppResult.Failed>(
                safeApiCall { client.requestJson<Map<String, String>>("data", maximumBytes = 128) }
            )
            withContext(Dispatchers.Default) { withTimeout(5_000) { stopped.await() } }
            assertTrue(written < 1_024)
        } finally {
            client.close()
            producer.cancelAndJoin()
        }
    }

    @Test
    fun cancellationWhileReadingAResponseDoesNotBecomeAnApiFailure() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val started = CompletableDeferred<Unit>()
        val client =
            createHttpClient(
                MockEngine {
                    started.complete(Unit)
                    respond(
                        channel,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://example.invalid/",
            )
        var diagnosed = false
        try {
            val request = async {
                safeApiCall(onException = { diagnosed = true }) {
                    client.requestJson<Map<String, String>>("data")
                }
            }
            started.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertFalse(diagnosed)
        } finally {
            client.close()
        }
    }

    @Test
    fun failureToReadAnErrorBodyRetainsTheKnownHttpStatusAndCause() = runTest {
        val cause = IOException("private socket detail")
        val channel = ByteChannel()
        val reading = CompletableDeferred<Unit>()
        val response =
            object : ByteReadChannel by channel {
                override suspend fun awaitContent(min: Int): Boolean {
                    reading.complete(Unit)
                    return channel.awaitContent(min)
                }
            }
        val client =
            createHttpClient(
                MockEngine { respond(response, HttpStatusCode.Conflict) },
                "https://example.invalid/",
            )
        val diagnostics = mutableListOf<Exception>()
        try {
            val request = async {
                safeApiCall(onException = diagnostics::add) { client.requestJson<Unit>("data") }
            }
            reading.await()
            channel.close(cause)
            val failure = assertIs<AppResult.Failed>(request.await()).failure
            assertEquals(FailureKind.Conflict, failure.kind)
            assertIs<IOException>(assertIs<ApiResponseException>(diagnostics.single()).cause)
            assertFalse(failure.message.contains("private"))
        } finally {
            client.close()
        }
    }
}
