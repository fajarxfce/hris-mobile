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
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIdentityTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val command =
        CollaborationSpaceIdentityCommand(
            "80000000-0000-4000-8000-000000000001",
            owner.identityBinding(),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2026-11-01T00:00:00Z"),
        )
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val receipt = """{"id":"${parent.accountId}","version":0}"""

    @Test
    fun bodylessNativeSelectionRetainsOperationAndClientHeadersAcrossOne401Replay() = runTest {
        var calls = 0
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${owner.spaceId}/identity",
                    request.url.encodedPath,
                )
                assertEquals(command.id, request.headers["Idempotency-Key"])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("5", request.headers["X-HRIS-Client-Build"])
                assertNull(request.headers["X-Company-Id"])
                assertNull(request.headers[HttpHeaders.Cookie])
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
                    respond(receipt, headers = headers)
                }
            }
        ) { repo ->
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(parent.accountId, 0)),
                repo.select(owner, command),
            )
            assertEquals(2, calls)
        }
    }

    @Test
    fun explicitFreshNativeAttemptUsesCurrentHeadersWithoutChangingStoredProvenance() = runTest {
        val current = parent.copy(sessionId = "fresh-native-login", nativeSessionId = owner.spaceId)
        withRepository(
            MockEngine { request ->
                assertEquals(command.id, request.headers["Idempotency-Key"])
                assertEquals("6", request.headers["X-HRIS-Client-Build"])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("Bearer initial-native", request.headers[HttpHeaders.Authorization])
                respond(receipt, headers = headers)
            },
            currentParent = current,
            clientBuild = 6,
        ) { repo ->
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(parent.accountId, 0)),
                repo.select(owner.copy(parent = current), command),
            )
            assertEquals(parent.nativeSessionId, command.binding.nativeSessionId)
            assertEquals(5L, command.clientBuild)
        }
    }

    @Test
    fun invalidNativeMetadataAccountSpaceAndRequestShapeNeverReachHttp() = runTest {
        withRepository(MockEngine { error("Invalid selection reached transport") }) { repo ->
            for (request in
                listOf(
                    command.copy(id = "../operation"),
                    command.copy(clientBuild = 0),
                    command.copy(binding = command.binding.copy(nativeSessionId = "invalid")),
                    command.copy(binding = command.binding.copy(accountId = owner.spaceId)),
                    command.copy(binding = command.binding.copy(spaceId = parent.accountId)),
                )) {
                assertIs<AppResult.Failed>(repo.select(owner, request))
            }
            assertIs<AppResult.Failed>(
                repo.select(
                    owner.copy(parent = parent.copy(audience = "https://foreign.example.test/")),
                    command,
                )
            )
        }
    }

    @Test
    fun wrongReceiptIdentityVersionAndOversizeBodyCannotConfirmSelection() = runTest {
        for (body in
            listOf(
                """{"id":"${owner.spaceId}","version":0}""",
                """{"id":"${parent.accountId}","version":1}""",
                "{}",
                "x".repeat(1025),
            )) {
            withRepository(MockEngine { respond(body, headers = headers) }) { repo ->
                assertIs<AppResult.Failed>(repo.select(owner, command))
            }
        }
    }

    @Test
    fun serverDenialStaysClassifiedAndSecond401StopsReplay() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond("""{"code":"collaboration_access_denied"}""", status, headers)
                }
            ) { repo ->
                val failure = assertIs<AppResult.Failed>(repo.select(owner, command)).failure
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
    fun cancellationStopsPendingTransportWithoutAReceipt() = runTest {
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
            val pending = async { repo.select(owner, command) }
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
