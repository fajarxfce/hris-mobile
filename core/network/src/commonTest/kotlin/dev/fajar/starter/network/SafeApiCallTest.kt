package dev.fajar.starter.network

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest

class SafeApiCallTest {
    @Test
    fun mapsHttpFailuresWithoutExposingServerText() = runTest {
        for ((status, kind) in
            listOf(
                401 to FailureKind.Unauthorized,
                429 to FailureKind.Service,
                503 to FailureKind.Service,
            )) {
            val client =
                createHttpClient(
                    MockEngine {
                        respond("secret-server-detail", HttpStatusCode.fromValue(status))
                    },
                    "https://example.test/",
                )
            val errors = mutableListOf<Exception>()
            val result = safeApiCall(onException = errors::add) { client.get("resource") }
            assertEquals(kind, assertIs<AppResult.Failed>(result).failure.kind)
            assertFalse(result.failure.message.contains("secret-server-detail"))
            assertEquals(1, errors.size)
            client.close()
        }
    }

    @Test
    fun includesMappingInsideTheBoundary() = runTest {
        val result = safeApiCall { error("private-payload") }
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(result).failure.kind)
        assertFalse(result.failure.message.contains("private-payload"))
    }

    @Test
    fun propagatesCancellationWithoutReportingFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reported = false
        val request = async {
            safeApiCall(onException = { reported = true }) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        request.cancelAndJoin()
        release.complete(Unit)
        assertTrue(request.isCancelled)
        assertFalse(reported)
    }

    @Test
    fun clientsHaveIndependentBaseUrls() = runTest {
        val seen = mutableListOf<String>()
        val clientA =
            createHttpClient(
                MockEngine {
                    seen += it.url.host
                    respond("ok")
                },
                "https://a.example/",
            )
        val clientB =
            createHttpClient(
                MockEngine {
                    seen += it.url.host
                    respond("ok")
                },
                "https://b.example/",
            )
        clientA.get("one")
        clientB.get("two")
        assertEquals(listOf("a.example", "b.example"), seen)
        clientA.close()
        clientB.close()
    }
}
