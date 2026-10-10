package dev.fajar.starter.network

import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class HttpClientOwnershipTest {
    @Test
    fun closingAClientReleasesItsDedicatedEngineAndStopsPendingIo() = runTest {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val client = createHttpClient(engine, "https://example.invalid/")
        val request = async { client.get("pending") }
        started.await()
        client.cancelAndClose()
        withContext(Dispatchers.Default) { withTimeout(5_000) { stopped.await() } }
        request.join()
        assertTrue(request.isCancelled)
        assertFalse(engine.coroutineContext.job.isActive)
        client.close()
    }

    @Test
    fun aConfigurationFailureAlsoReleasesTheAllocatedEngine() {
        val engine = MockEngine { respond("unused") }
        assertFailsWith<IllegalArgumentException> {
            createHttpClient(engine, HttpClientSettings("https://example.invalid/")) {
                throw IllegalArgumentException("Invalid test configuration")
            }
        }
        assertFalse(engine.coroutineContext.job.isActive)
    }
}
