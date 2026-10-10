package dev.fajar.starter.network

import dev.fajar.starter.observability.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class HttpDiagnosticsTest {
    @Test
    fun correlationReachesOnlyTheExactApiOriginWithoutBaggage() = runTest {
        val received = mutableListOf<Headers>()
        val client =
            createHttpClient(
                MockEngine {
                    received += it.headers
                    respond("ok")
                },
                "https://api.example/",
            )
        try {
            val root =
                measureOperation(PerformanceOperation.Sync) {
                    client.get("items") {
                        header("baggage", "secret")
                        header("tracestate", "secret")
                    }
                    client.get("https://external.example/file?token=private") {
                        header("traceparent", "untrusted")
                    }
                    client.get("https://api.example:8443/items")
                    checkNotNull(currentCoroutineContext()[OperationTrace])
                }
            val header = checkNotNull(received[0]["traceparent"])
            assertTrue(header.matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-01")))
            assertEquals(root.traceId, header.split('-')[1])
            assertNull(received[0]["baggage"])
            assertNull(received[0]["tracestate"])
            assertNull(received[1]["traceparent"])
            assertNull(received[2]["traceparent"])
        } finally {
            client.close()
        }
    }

    @Test
    fun transportFailureAndCancellationBothFinishTheirSamples() = runTest {
        val outcomes = mutableListOf<PerformanceOutcome>()
        PerformanceMonitoring.install(PerformanceSink { PerformanceSample { outcomes += it } })
        val started = CompletableDeferred<Unit>()
        val client =
            createHttpClient(
                MockEngine {
                    if (it.url.encodedPath == "/failed") error("private error")
                    started.complete(Unit)
                    awaitCancellation()
                },
                "https://api.example/",
            )
        try {
            assertFailsWith<IllegalStateException> { client.get("failed") }
            val pending = launch { client.get("pending") }
            started.await()
            pending.cancelAndJoin()
            assertEquals(listOf(PerformanceOutcome.Failed, PerformanceOutcome.Cancelled), outcomes)
        } finally {
            client.close()
            PerformanceMonitoring.install(PerformanceSink { null })
        }
    }
}
