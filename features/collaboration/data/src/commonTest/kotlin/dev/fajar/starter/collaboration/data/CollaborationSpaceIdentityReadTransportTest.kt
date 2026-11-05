package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceIdentityApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceIdentityRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceIdentityRepository
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

class CollaborationSpaceIdentityReadTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val company = "30000000-0000-4000-8000-000000000001"
    private val realm = "matrix.hris.internal"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun body(legacy: Boolean = true, server: String = realm): String {
        val prefix =
            if (legacy) "hris_${company.replace("-", "")}"
            else "hrisg_${owner.spaceId.replace("-", "")}"
        val legacyCompany = if (legacy) "\"$company\"" else "null"
        return """{"identity":{"spaceId":"${owner.spaceId}","accountId":"${parent.accountId}",
            "serverName":"$server","matrixUserId":"@${prefix}_${parent.accountId.replace("-", "")}:$server",
            "origin":"${if (legacy) "LEGACY" else "SPACE"}","legacyCompanyId":$legacyCompany,
            "version":0,"createdAt":"2020-01-01T00:00:00Z"}}"""
    }

    @Test
    fun nativeStatusHasNoCompanyCookieBodyOrIdempotencyAndOnlyOne401Replay() = runTest {
        var calls = 0
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${owner.spaceId}/identity",
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
                    respond(body(), headers = headers)
                }
            }
        ) { repo ->
            val value =
                assertIs<AppResult.Success<CollaborationSpaceIdentityObservation>>(repo.read(owner))
                    .value
            assertEquals(owner, value.owner)
            val identity = assertNotNull(value.identity)
            assertEquals(CollaborationSpaceIdentityOrigin.LEGACY, identity.origin)
            assertEquals(company, identity.legacyCompanyId)
            assertEquals(parent.accountId, identity.accountId)
            assertEquals(2, calls)
            assertFalse(identity.toString().contains(realm))
        }
    }

    @Test
    fun absentIdentityUnderFreshLoginRemainsAnExplicitNullObservation() = runTest {
        val fresh = parent.copy(sessionId = "new-local-login", nativeSessionId = owner.spaceId)
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                respond("""{"identity":null}""", headers = headers)
            },
            currentParent = fresh,
        ) { repo ->
            val current = owner.copy(parent = fresh)
            assertEquals(
                AppResult.Success(CollaborationSpaceIdentityObservation(current, null)),
                repo.read(current),
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun spaceIdentityAndRetainedLegacyMetadataKeepCanonicalUsersWithoutChoosingEndpoint() =
        runTest {
            for (legacy in listOf(false, true)) for (server in
                listOf(realm, "$realm:8448", "[::1]:8448")) {
                var calls = 0
                withRepository(
                    MockEngine {
                        calls++
                        assertEquals(Url(SETUP_BASE).host, it.url.host)
                        respond(body(legacy, server), headers = headers)
                    }
                ) { repo ->
                    val identity =
                        assertNotNull(
                            assertIs<AppResult.Success<CollaborationSpaceIdentityObservation>>(
                                    repo.read(owner)
                                )
                                .value
                                .identity
                        )
                    assertEquals(server, identity.serverName)
                    assertEquals(if (legacy) company else null, identity.legacyCompanyId)
                    assertEquals(
                        if (legacy) CollaborationSpaceIdentityOrigin.LEGACY
                        else CollaborationSpaceIdentityOrigin.SPACE,
                        identity.origin,
                    )
                    assertEquals(1, calls)
                }
            }
        }

    @Test
    fun malformedOrContradictorySelectionCannotBecomeIdentityMetadata() = runTest {
        val original = body()
        val malformed =
            listOf(
                "{}",
                "x".repeat(4097),
                original.replace("LEGACY", "UNKNOWN"),
                original.replace("\"version\":0", "\"version\":1"),
                original.replace("\"version\":0", "\"version\":-1"),
                original.replace(owner.spaceId, parent.nativeSessionId),
                original.replace("\"${parent.accountId}\"", "\"${parent.nativeSessionId}\""),
                original.replace("\"$company\"", "null"),
                body(false).replace("\"legacyCompanyId\":null", "\"legacyCompanyId\":\"$company\""),
                original.replace("@hris_", "@hrisg_"),
                original.replace("2020-01-01", "1960-01-01"),
                original.replace("2020-01-01", "invalid-time"),
                body(server = "matrix.hris.internal/other"),
                body(server = "attacker@example.test"),
                body(server = "matrix.hris.internal?query"),
                body(server = "matrix.hris.internal#fragment"),
                body(server = "matrix.hris.internal:0"),
                body(server = "matrix.hris.internal:65536"),
                body(server = "https://matrix.hris.internal"),
                body(server = "x".repeat(181)),
            )
        for ((i, invalid) in malformed.withIndex()) withRepository(
            MockEngine { respond(invalid, headers = headers) }
        ) {
            assertIs<AppResult.Failed>(it.read(owner), "Malformed identity $i")
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
                )) assertIs<AppResult.Failed>(repo.read(invalid))
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
                val failure = assertIs<AppResult.Failed>(repo.read(owner)).failure
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
            val pending = async { repo.read(owner) }
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
        action: suspend (RemoteCollaborationSpaceIdentityRepository) -> Unit,
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
                RemoteCollaborationSpaceIdentityRepository(
                    ApiCollaborationSpaceIdentityRemoteSource(
                        CollaborationSpaceIdentityApi(client)
                    ),
                    IdentityServer(SETUP_BASE, "Fixture"),
                )
            )
        } finally {
            client.close()
        }
    }
}
