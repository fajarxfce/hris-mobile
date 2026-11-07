package dev.fajar.starter

import dev.fajar.inspector.*
import dev.fajar.starter.app.di.applicationFirebaseModule
import dev.fajar.starter.identity.data.firebase.datasources.*
import dev.fajar.starter.network.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication

class FirebaseInspectionModuleTest {
    @Test
    fun existingTrailingEngineFactoryRemainsLazyAndOwnsTheOnlyRequest() = runTest {
        var acquisitions = 0
        var requests = 0
        val container = koinApplication {
            modules(
                applicationFirebaseModule(
                    UnavailableFirebaseClientOptionsSource(),
                    UnavailableGoogleCredentialSource(),
                ) {
                    acquisitions++
                    MockEngine {
                        requests++
                        respond("unchanged", HttpStatusCode.OK)
                    }
                }
            )
        }
        try {
            assertEquals(0, acquisitions)
            val client = container.koin.get<HttpClient>(named(HttpClients.FirebaseAuth))
            assertEquals("unchanged", client.get("https://example.invalid/fixture").bodyAsText())
            assertEquals(1, acquisitions)
            assertEquals(1, requests)
        } finally {
            container.close()
        }
    }

    @Test
    fun injectedCaptureRecordsTheActualEngineResponseAndFollowsReset() = runTest {
        val inspection = NetworkInspection()
        var requests = 0
        val container = koinApplication {
            modules(
                applicationFirebaseModule(
                    UnavailableFirebaseClientOptionsSource(),
                    UnavailableGoogleCredentialSource(),
                    inspection = inspection,
                ) {
                    MockEngine {
                        requests++
                        assertEquals("synthetic-key", it.url.parameters["key"])
                        assertNull(it.headers["traceparent"])
                        respond("original response", HttpStatusCode.OK)
                    }
                }
            )
        }
        try {
            val client = container.koin.get<HttpClient>(named(HttpClients.FirebaseAuth))
            val first = assertNotNull(inspection.recorders.value)
            repeat(2) { index ->
                assertEquals(
                    "original response",
                    client.get("https://example.invalid/fixture?key=synthetic-key").bodyAsText(),
                )
                val trace =
                    assertNotNull(inspection.recorders.value).snapshots.value.traces.single()
                assertEquals(NetworkSource.KTOR, trace.request.source)
                assertEquals(listOf("key"), trace.request.endpoint.queryNames)
                assertEquals(NetworkOutcome.Response(200), trace.outcome)
                if (index == 0) assertTrue(inspection.reset())
            }
            assertEquals(2, requests)
            assertTrue(first.snapshots.value.closed)
            assertTrue(first.snapshots.value.traces.isEmpty())
        } finally {
            container.close()
            inspection.close()
        }
    }
}
