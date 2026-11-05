package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceDeviceApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceDeviceRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceDeviceRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceDeviceTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val body =
        """{
      "device": {"id":"80000000-0000-4000-8000-000000000001","version":2,
        "state":"ACTIVE","platform":"ANDROID","origin":"LEGACY","label":"Employee phone",
        "provisioning":{"revision":1,"state":"SUCCEEDED","attempts":2,
          "expiresAt":"2026-10-01T00:05:00Z","retryNotBefore":null,
          "lastOutcome":{"revision":1,"state":"SUCCEEDED","at":"2026-10-01T00:01:00Z","failure":null}},
        "observedAt":"2026-11-05T00:00:00Z",
        "gateway":{"id":"90000000-0000-4000-8000-000000000001","version":3,"clientBuild":5,
          "accessExpiresAt":"2026-10-01T00:15:00Z","expiresAt":"2026-10-01T01:00:00Z","revokedAt":null}}
    }"""

    @Test
    fun nativeStatusHasNoCompanyCookieBodyOrIdempotencyAndOnlyOne401Replay() = runTest {
        var calls = 0
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${owner.spaceId}/native-device",
                    request.url.encodedPath,
                )
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("5", request.headers["X-HRIS-Client-Build"])
                for (key in
                    listOf("X-Company-Id", "Idempotency-Key", HttpHeaders.Cookie)) assertNull(
                    request.headers[key]
                )
                assertTrue(request.url.parameters.isEmpty())
                assertTrue(request.body.toByteArray().isEmpty())
                if (calls++ == 0) {
                    assertEquals(
                        "Bearer initial-native",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respond("""{"code":"session_expired"}""", HttpStatusCode.Unauthorized, headers)
                } else {
                    assertEquals(
                        "Bearer renewed-native",
                        request.headers[HttpHeaders.Authorization],
                    )
                    respond(body, headers = headers)
                }
            }
        ) { repo ->
            val value =
                assertIs<AppResult.Success<CollaborationSpaceDeviceObservation>>(repo.status(owner))
                    .value
            assertEquals(owner, value.owner)
            val device = assertNotNull(value.device)
            assertEquals(CollaborationSpaceDeviceOrigin.LEGACY, device.origin)
            assertEquals(
                CollaborationProvisioningState.SUCCEEDED,
                device.provisioning.lastOutcome?.state,
            )
            assertTrue(assertNotNull(device.gateway).expiresAt < device.observedAt)
            assertEquals(2, calls)
            assertFalse(device.toString().contains("Employee phone"))
        }
    }

    @Test
    fun absentDeviceUnderFreshLoginRemainsAnExplicitNullObservation() = runTest {
        val fresh = parent.copy(sessionId = "new-local-login", nativeSessionId = owner.spaceId)
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                respond("""{"device":null}""", headers = headers)
            },
            currentParent = fresh,
        ) { repo ->
            val current = owner.copy(parent = fresh)
            assertEquals(
                AppResult.Success(CollaborationSpaceDeviceObservation(current, null)),
                repo.status(current),
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun expiredQueuedWorkAndRetriedWorkRetainTheirDifferentOutcomes() = runTest {
        val prefix =
            """{"device":{"id":"80000000-0000-4000-8000-000000000001","version":1,"state":"PROVISIONING","platform":"ANDROID","origin":"SPACE","label":"Phone","observedAt":"2026-11-05T00:00:00Z","gateway":null,"provisioning":{ """
        val suffix =
            ""","expiresAt":"2026-10-01T00:05:00Z","retryNotBefore":"2026-10-01T00:05:00Z"}}}"""
        for (head in
            listOf(
                """"revision":0,"state":"QUEUED","attempts":0,"lastOutcome":null""",
                """"revision":1,"state":"RUNNING","attempts":1,"lastOutcome":{"revision":0,"state":"FAILED","at":"2026-10-01T00:01:00Z","failure":"PROVIDER_UNAVAILABLE"}""",
            )) withRepository(MockEngine { respond(prefix + head + suffix, headers = headers) }) {
            repo ->
            val device =
                assertNotNull(
                    assertIs<AppResult.Success<CollaborationSpaceDeviceObservation>>(
                            repo.status(owner)
                        )
                        .value
                        .device
                )
            assertTrue(device.provisioning.expiresAt < device.observedAt)
            assertEquals(CollaborationDeviceState.PROVISIONING, device.state)
        }
    }

    @Test
    fun malformedEnumsProvenanceVersionsAndContradictoryProgressCannotBecomeStatus() = runTest {
        val malformed =
            listOf(
                "{}",
                "x".repeat(8193),
                body.replace("LEGACY", "UNKNOWN"),
                body.replace("ANDROID", "WEB"),
                body.replace("Employee phone", " "),
                body.replace("Employee phone", "x".repeat(65)),
                body.replace("Employee phone", "Phone\\nname"),
                body.replace("80000000-0000-4000-8000-000000000001", "../device"),
                body.replace("\"version\":2", "\"version\":1000000"),
                body.replace("\"revision\":1", "\"revision\":1000000"),
                body.replace("\"attempts\":2", "\"attempts\":0"),
                body.replace("\"attempts\":2", "\"attempts\":9"),
                body.replace("SUCCEEDED", "FAILED"),
                body.replace("SUCCEEDED", "RUNNING"),
                body.replace("\"failure\":null", "\"failure\":\"ACCESS_DENIED\""),
                body.replace("\"state\":\"ACTIVE\"", "\"state\":\"PROVISIONING\""),
                body.replace(
                    "\"retryNotBefore\":null",
                    "\"retryNotBefore\":\"2026-10-01T00:05:00Z\"",
                ),
                body.replace("2026-10-01T00:01:00Z", "2026-12-01T00:01:00Z"),
                body.replace("2026-10-01T01:00:00Z", "2026-10-01T00:01:00Z"),
                body.replace("\"clientBuild\":5", "\"clientBuild\":1000000000"),
                body.replace("\"revokedAt\":null", "\"revokedAt\":\"2026-12-01T00:01:00Z\""),
            )
        for ((index, invalid) in malformed.withIndex()) withRepository(
            MockEngine { respond(invalid, headers = headers) }
        ) { repo ->
            assertIs<AppResult.Failed>(repo.status(owner), "Malformed response $index")
        }
    }

    @Test
    fun invalidRequestOwnershipCannotReachHttp() = runTest {
        withRepository(MockEngine { error("Invalid request reached HTTP") }) { repo ->
            for (invalid in
                listOf(
                    owner.copy(spaceId = "../space"),
                    owner.copy(parent = parent.copy(sessionId = "bad session")),
                    owner.copy(parent = parent.copy(audience = "https://elsewhere.example/")),
                    owner.copy(parent = parent.copy(accountId = "foreign")),
                    owner.copy(parent = parent.copy(nativeSessionId = "invalid")),
                )) assertIs<AppResult.Failed>(repo.status(invalid))
        }
    }

    @Test
    fun denialClassificationAndSecond401TerminateWithoutPolling() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond("""{"code":"collaboration_access_denied"}""", status, headers)
                }
            ) { repo ->
                val failure = assertIs<AppResult.Failed>(repo.status(owner)).failure
                assertEquals(
                    if (status == HttpStatusCode.Forbidden) FailureKind.AccessDenied
                    else FailureKind.Unauthorized,
                    failure.kind,
                )
                assertEquals(if (status == HttpStatusCode.Forbidden) 1 else 2, calls)
            }
        }
    }

    @Test
    fun cancelledPendingReadStopsTransport() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        withRepository(
            MockEngine {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        ) { repo ->
            val pending = async { repo.status(owner) }
            entered.await()
            pending.cancelAndJoin()
            stopped.await()
            assertTrue(pending.isCancelled)
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        currentParent: CollaborationNativeParent = parent,
        clientBuild: Long = 5,
        action: suspend (RemoteCollaborationSpaceDeviceRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                defaultRequest {
                    header("X-HRIS-Client-Platform", "ANDROID")
                    header("X-HRIS-Client-Build", clientBuild)
                }
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(currentParent.sessionId, session)
                        AppResult.Success(
                            if (rejected == null) "initial-native" else "renewed-native"
                        )
                    }
                }
            }
        try {
            action(
                RemoteCollaborationSpaceDeviceRepository(
                    ApiCollaborationSpaceDeviceRemoteSource(CollaborationSpaceDeviceApi(client)),
                    IdentityServer(SETUP_BASE, "Fixture"),
                )
            )
        } finally {
            client.close()
        }
    }
}
