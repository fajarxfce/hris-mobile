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
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationSpaceRegistrationTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val company = "30000000-0000-4000-8000-000000000001"
    private val originalDevice = "80000000-0000-4000-8000-000000000001"
    private val device = "90000000-0000-4000-8000-000000000001"
    private val user =
        "@hris_${company.replace("-", "")}_${parent.accountId.replace("-", "")}:matrix.hris.internal"
    private val command =
        CollaborationSpaceRegistrationCommand(
            "60000000-0000-4000-8000-000000000001",
            CollaborationSpaceDeviceBinding(
                parent.audience,
                parent.accountId,
                owner.spaceId,
                parent.nativeSessionId,
            ),
            CollaborationNativePlatform.ANDROID,
            4,
            Instant.parse("2020-01-01T00:00:00Z"),
            CollaborationSpaceIdentity(
                owner.spaceId,
                parent.accountId,
                "matrix.hris.internal",
                user,
                CollaborationSpaceIdentityOrigin.LEGACY,
                company,
                0,
                Instant.parse("2020-01-01T00:00:00Z"),
            ),
            "Employee phone",
            CollaborationSpaceDeviceOrigin.LEGACY,
            CollaborationSpaceLegacySdk(
                CollaborationSetupBinding(
                    parent.audience,
                    parent.accountId,
                    company,
                    parent.nativeSessionId,
                ),
                originalDevice,
                CollaborationLocalDeviceIdentity(
                    user,
                    "HRIS_${originalDevice.replace("-", "")}",
                    "a".repeat(64),
                ),
            ),
        )
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val receipt = """{"id":"$device","version":2}"""

    @Test
    fun nativePostKeepsOriginalBodyOperationAndCurrentHeadersAcrossOne401Replay() = runTest {
        var calls = 0
        val bodies = mutableListOf<String>()
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${owner.spaceId}/native-device",
                    request.url.encodedPath,
                )
                assertEquals(command.id, request.headers["Idempotency-Key"])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("5", request.headers["X-HRIS-Client-Build"])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers["X-Company-Id"])
                assertTrue(request.url.parameters.isEmpty())
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                val json = Json.parseToJsonElement(body).jsonObject
                assertEquals(
                    setOf("platform", "label", "origin", "legacy", "legacyKeyFingerprint"),
                    json.keys,
                )
                assertEquals("Employee phone", json.getValue("label").jsonPrimitive.content)
                assertEquals(
                    company,
                    json.getValue("legacy").jsonObject.getValue("companyId").jsonPrimitive.content,
                )
                assertEquals(
                    originalDevice,
                    json.getValue("legacy").jsonObject.getValue("deviceId").jsonPrimitive.content,
                )
                assertEquals(
                    "a".repeat(64),
                    json.getValue("legacyKeyFingerprint").jsonPrimitive.content,
                )
                assertFalse(body.contains(user))
                assertFalse(body.contains(parent.nativeSessionId))
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
                AppResult.Success(CollaborationSetupReceipt(device, 2)),
                repo.register(owner, command),
            )
            assertEquals(2, calls)
            assertEquals(bodies.first(), bodies.last())
        }
    }

    @Test
    fun sameNativeFamilyNewLocalLoginMayReplayButAnotherFamilyNeverReachesHttp() = runTest {
        val current = parent.copy(sessionId = "fresh-local")
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                respond(receipt, headers = headers)
            },
            currentParent = current,
        ) { repo ->
            assertIs<AppResult.Success<*>>(repo.register(owner.copy(parent = current), command))
            assertIs<AppResult.Failed>(
                repo.register(
                    owner.copy(parent = current.copy(nativeSessionId = owner.spaceId)),
                    command,
                )
            )
            assertEquals(1, calls)
        }
    }

    @Test
    fun invalidProvenanceLabelOwnerAndRequestIdentityNeverReachHttp() = runTest {
        val original = assertNotNull(command.legacy)
        withRepository(MockEngine { error("Invalid registration reached HTTP") }) { repo ->
            for (invalid in
                listOf(
                    command.copy(id = "invalid"),
                    command.copy(label = "Phone\nname"),
                    command.copy(
                        legacy =
                            original.copy(identity = original.identity.copy(fingerprint = "bad"))
                    ),
                    command.copy(
                        provider =
                            command.provider.copy(matrixUserId = "@other:matrix.hris.internal")
                    ),
                    command.copy(binding = command.binding.copy(accountId = owner.spaceId)),
                    command.copy(origin = CollaborationSpaceDeviceOrigin.SPACE),
                )) assertIs<AppResult.Failed>(repo.register(owner, invalid))
            for (invalid in
                listOf(
                    owner.copy(spaceId = "../space"),
                    owner.copy(parent = parent.copy(sessionId = "bad session")),
                    owner.copy(parent = parent.copy(audience = "https://other.example/")),
                )) assertIs<AppResult.Failed>(repo.register(invalid, command))
        }
    }

    @Test
    fun malformedReceiptCannotConfirmRegistration() = runTest {
        for (body in
            listOf(
                "{}",
                "x".repeat(1025),
                receipt.replace(device, "invalid"),
                receipt.replace("\"version\":2", "\"version\":-1"),
                receipt.replace("\"version\":2", "\"version\":1000000"),
            )) {
            withRepository(MockEngine { respond(body, headers = headers) }) {
                assertIs<AppResult.Failed>(it.register(owner, command))
            }
        }
    }

    @Test
    fun typedSpaceDeviceTransportKeepsLegacyUserMetadataLocalWithoutInventingProvenance() =
        runTest {
            val input = command.copy(origin = CollaborationSpaceDeviceOrigin.SPACE, legacy = null)
            withRepository(
                MockEngine { request ->
                    val json =
                        Json.parseToJsonElement(request.body.toByteArray().decodeToString())
                            .jsonObject
                    assertEquals("SPACE", json.getValue("origin").jsonPrimitive.content)
                    assertTrue(json["legacy"] == null || json["legacy"] == JsonNull)
                    assertTrue(
                        json["legacyKeyFingerprint"] == null ||
                            json["legacyKeyFingerprint"] == JsonNull
                    )
                    assertFalse(json.containsKey("provider"))
                    respond(receipt, headers = headers)
                }
            ) {
                assertIs<AppResult.Success<*>>(it.register(owner, input))
            }
        }

    @Test
    fun deniedAndSecond401ResponsesPreserveClassificationAndStop() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond("""{"code":"collaboration_access_denied"}""", status, headers)
                }
            ) { repo ->
                val failure = assertIs<AppResult.Failed>(repo.register(owner, command)).failure
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
    fun cancellationStopsPendingRegistrationWithoutTransportRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var calls = 0
        withRepository(
            MockEngine {
                try {
                    calls++
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        ) { repo ->
            val task = async { repo.register(owner, command) }
            entered.await()
            task.cancelAndJoin()
            stopped.await()
            assertEquals(1, calls)
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
