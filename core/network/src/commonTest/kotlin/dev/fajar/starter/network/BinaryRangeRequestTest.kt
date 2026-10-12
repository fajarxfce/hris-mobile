package dev.fajar.starter.network

import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class BinaryRangeRequestTest {
    @Test
    fun exactRangesRetainSessionAndConditionalHeadersWithoutOffsetOverflow() = runTest {
        val offset = Long.MAX_VALUE - 3
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(
                        "bytes=$offset-${Long.MAX_VALUE - 1}",
                        request.headers[HttpHeaders.Range],
                    )
                    assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                    assertEquals("\"r1\"", request.headers[HttpHeaders.IfRange])
                    assertEquals("session", request.attributes[RequestSession])
                    respond(
                        byteArrayOf(1, 2, 3),
                        HttpStatusCode.PartialContent,
                        rangeHeaders(
                            range = "bytes $offset-${Long.MAX_VALUE - 1}/${Long.MAX_VALUE}",
                            length = "3",
                        ),
                    )
                },
                "https://api.example/",
            )
        try {
            val bytes =
                client.requestBinaryRange("content", "\"r1\"", offset, Long.MAX_VALUE, 4) {
                    forSession("session")
                    header(HttpHeaders.IfRange, "\"r1\"")
                }
            assertContentEquals(byteArrayOf(1, 2, 3), bytes)
            bytes.fill(0)
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidRangesAndWeakOrUnboundedValidatorsFailBeforeTransport() = runTest {
        var requests = 0
        val client =
            createHttpClient(
                MockEngine {
                    requests++
                    error("No request should leave the client")
                },
                "https://api.example/",
            )
        try {
            for (tag in
                listOf("r1", "W/\"r1\"", "\"r\n1\"", "\"r\"1\"", "\"" + "x".repeat(257) + "\"")) {
                assertFailsWith<IllegalArgumentException> {
                    client.requestBinaryRange("content", tag, 0, 8, 4)
                }
            }
            for ((offset, size, maximum) in
                listOf(
                    Triple(-1L, 8L, 4),
                    Triple(8L, 8L, 4),
                    Triple(0L, 0L, 4),
                    Triple(0L, 8L, 0),
                    Triple(0L, 8L, 16_777_217),
                )) {
                assertFailsWith<IllegalArgumentException> {
                    client.requestBinaryRange("content", "\"r1\"", offset, size, maximum)
                }
            }
            assertEquals(0, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun wholeResponseFallbackChangedMetadataAndNonexactBodiesAreRejected() = runTest {
        val cases =
            listOf(
                ResponseCase(status = HttpStatusCode.OK),
                ResponseCase(headers = rangeHeaders(tag = "\"r2\"")),
                ResponseCase(headers = rangeHeaders(tag = "W/\"r1\"")),
                ResponseCase(headers = rangeHeaders(range = "bytes 4-7/8")),
                ResponseCase(headers = rangeHeaders(range = "bytes 0-3/9")),
                ResponseCase(headers = rangeHeaders(length = "5")),
                ResponseCase(headers = rangeHeaders(length = "-1")),
                ResponseCase(headers = rangeHeaders(length = "invalid")),
                ResponseCase(
                    headers =
                        Headers.build {
                            appendAll(rangeHeaders())
                            append(HttpHeaders.ContentEncoding, "gzip")
                        }
                ),
                ResponseCase(
                    headers =
                        Headers.build {
                            appendAll(rangeHeaders())
                            append(HttpHeaders.ETag, "\"r1\"")
                        }
                ),
                ResponseCase(
                    headers =
                        Headers.build {
                            appendAll(rangeHeaders())
                            append(HttpHeaders.ContentRange, "bytes 0-3/8")
                        }
                ),
                ResponseCase(
                    headers =
                        Headers.build {
                            appendAll(rangeHeaders(length = "4"))
                            append(HttpHeaders.ContentLength, "4")
                        }
                ),
                ResponseCase(length = 3),
                ResponseCase(length = 5),
            )
        for ((index, case) in cases.withIndex()) {
            val client =
                createHttpClient(
                    MockEngine { respond(ByteArray(case.length), case.status, case.headers) },
                    "https://api.example/",
                )
            try {
                val result = safeApiCall { client.requestBinaryRange("content", "\"r1\"", 0, 8, 4) }
                assertIs<AppResult.Failed>(result, "Invalid response $index was accepted")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun authenticatedRejectionRetainsTheLocalizableProblemWithoutPrivateText() = runTest {
        val client =
            createHttpClient(
                MockEngine {
                    respond(
                        """{"code":"mfa_required","detail":"private content location"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                },
                "https://api.example/",
            )
        try {
            val result = safeApiCall { client.requestBinaryRange("content", "\"r1\"", 0, 8, 4) }
            val failure = assertIs<AppResult.Failed>(result).failure
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertEquals("mfa_required", failure.code)
            assertFalse(failure.message.contains("private"))
        } finally {
            client.close()
        }
    }

    @Test
    fun oversizedUnfinishedResponseIsCancelledWithoutDrainingItsProducer() = runTest {
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
                    // The response owner cancels the stream when the range limit is exceeded.
                } finally {
                    stopped.complete(Unit)
                }
            }
        val client =
            createHttpClient(
                MockEngine { respond(channel, HttpStatusCode.PartialContent, rangeHeaders()) },
                "https://api.example/",
            )
        try {
            assertIs<AppResult.Failed>(
                safeApiCall { client.requestBinaryRange("content", "\"r1\"", 0, 8, 4) }
            )
            withContext(Dispatchers.Default) { withTimeout(5_000) { stopped.await() } }
            assertTrue(written < 1_024)
        } finally {
            client.close()
            producer.cancelAndJoin()
        }
    }

    @Test
    fun cancellingAPendingBodyDoesNotPublishBytesOrDiagnoseAnApiFailure() = runTest {
        val channel = ByteChannel(autoFlush = true)
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
                MockEngine { respond(response, HttpStatusCode.PartialContent, rangeHeaders()) },
                "https://api.example/",
            )
        var diagnosed = false
        try {
            val request = async {
                safeApiCall(onException = { diagnosed = true }) {
                    client.requestBinaryRange("content", "\"r1\"", 0, 8, 4)
                }
            }
            reading.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertFalse(diagnosed)
            assertTrue(channel.isClosedForRead)
        } finally {
            client.close()
        }
    }

    @Test
    fun acceptedHeadMetadataDoesNotConsumeARepresentationBody() = runTest {
        val channel = ByteChannel()
        var reads = 0
        val response =
            object : ByteReadChannel by channel {
                override suspend fun awaitContent(min: Int): Boolean {
                    reads++
                    return channel.awaitContent(min)
                }
            }
        val client =
            createHttpClient(
                MockEngine {
                    respond(
                        response,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, "104857600"),
                    )
                },
                "https://api.example/",
            )
        try {
            val size =
                client
                    .prepareHead("content") { expectSuccess = false }
                    .execute {
                        requireApiStatus(it, HttpStatusCode.OK)
                        it.headers[HttpHeaders.ContentLength]
                    }
            assertEquals("104857600", size)
            assertEquals(0, reads)
            assertTrue(channel.isClosedForRead)
        } finally {
            client.close()
        }
    }

    private data class ResponseCase(
        val status: HttpStatusCode = HttpStatusCode.PartialContent,
        val headers: Headers = rangeHeaders(),
        val length: Int = 4,
    )
}

private fun rangeHeaders(
    tag: String = "\"r1\"",
    range: String = "bytes 0-3/8",
    length: String? = null,
): Headers =
    Headers.build {
        append(HttpHeaders.ETag, tag)
        append(HttpHeaders.ContentRange, range)
        length?.let { append(HttpHeaders.ContentLength, it) }
    }
