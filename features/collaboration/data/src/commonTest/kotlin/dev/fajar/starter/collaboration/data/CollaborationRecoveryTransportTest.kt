package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationRecoveryApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationRecoveryRemoteSource
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationRecoveryRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationRecoveryTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val device =
        """{"companyId":"${setupOwner.companyId}","deviceId":"$SETUP_DEVICE","version":1,"state":"ACTIVE","platform":"ANDROID"}"""
    private val page =
        """{"accountId":"${parent.accountId}","nativeSessionId":"${parent.nativeSessionId}","devices":[$device],"nextAfterCompanyId":null}"""
    private val admission =
        """{"companyId":"${setupOwner.companyId}","accountId":"${parent.accountId}","deviceId":"$SETUP_DEVICE","deviceVersion":1,"nativeSessionId":"${parent.nativeSessionId}","nativeSessionVersion":0,"credentialVersion":0,"admittedAt":"2026-11-02T00:00:00Z","expiresAt":"2026-11-02T00:01:00Z"}"""
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun directoryAndAdmissionUseOnlyOriginatingNativeSessionAndBoundedRoutes() = runTest {
        var calls = 0
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("Bearer fictional-native", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                if (calls++ == 0) {
                    assertEquals("/api/v1/collaboration/native-devices", request.url.encodedPath)
                    assertEquals(setOf("limit"), request.url.parameters.names())
                    assertEquals("8", request.url.parameters["limit"])
                    respond(page, headers = headers)
                } else {
                    assertEquals(
                        "/api/v1/companies/${setupOwner.companyId}/collaboration/native-device/admission",
                        request.url.encodedPath,
                    )
                    assertTrue(request.url.parameters.isEmpty())
                    respond(admission, headers = headers)
                }
            }
        ) { repository ->
            val found =
                assertIs<AppResult.Success<CollaborationDevicePage>>(
                        repository.devices(parent, null, 8)
                    )
                    .value
            assertEquals(parent, found.parent)
            assertEquals(SETUP_DEVICE, found.devices.single().deviceId)
            val observed =
                assertIs<AppResult.Success<CollaborationAdmissionObservation>>(
                        repository.admission(setupOwner)
                    )
                    .value
            assertEquals(setupOwner, observed.owner)
            assertEquals(SETUP_DEVICE, observed.deviceId)
        }
        assertEquals(2, calls)
    }

    @Test
    fun nativeRecoveryRejectsMalformedOwnersBeforeTransport() = runTest {
        withRepository(MockEngine { error("Invalid ownership must not reach transport") }) {
            repository ->
            for (invalid in
                listOf(
                    parent.copy(audience = "https://other.example.test/"),
                    parent.copy(accountId = "bad"),
                    parent.copy(
                        nativeSessionId = parent.nativeSessionId.uppercase().replace('3', 'Z')
                    ),
                    parent.copy(sessionId = "invalid session"),
                )) assertIs<AppResult.Failed>(repository.devices(invalid, null, 8))
            for (limit in listOf(0, 33)) assertIs<AppResult.Failed>(
                repository.devices(parent, null, limit)
            )
            assertIs<AppResult.Failed>(repository.devices(parent, "invalid", 8))
            assertIs<AppResult.Failed>(
                repository.admission(setupOwner.copy(companyId = "../foreign"))
            )
        }
    }

    @Test
    fun directoryRejectsForeignIdentityUnorderedDuplicatesOversizeAndNonprogressingCursor() =
        runTest {
            val original = Json.parseToJsonElement(page).jsonObject
            val rawDevice = Json.parseToJsonElement(device).jsonObject
            fun changed(vararg values: Pair<String, JsonElement>) =
                JsonObject(original + values).toString()
            val second =
                JsonObject(
                    rawDevice +
                        ("companyId" to JsonPrimitive("99999999-9999-4999-8999-999999999999"))
                )
            for (body in
                listOf(
                    changed("accountId" to JsonPrimitive(SETUP_DEVICE)),
                    changed("nativeSessionId" to JsonPrimitive(SETUP_DEVICE)),
                    changed("devices" to JsonArray(listOf(rawDevice, rawDevice))),
                    changed("devices" to JsonArray(listOf(second, rawDevice))),
                    changed("devices" to JsonArray(List(9) { rawDevice })),
                    changed("nextAfterCompanyId" to JsonPrimitive(setupOwner.companyId)),
                    changed(
                        "devices" to
                            JsonArray(
                                listOf(JsonObject(rawDevice + ("version" to JsonPrimitive(-1))))
                            )
                    ),
                    changed(
                        "devices" to
                            JsonArray(
                                listOf(JsonObject(rawDevice + ("state" to JsonPrimitive("OTHER"))))
                            )
                    ),
                )) withRepository(MockEngine { respond(body, headers = headers) }) { repository ->
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(repository.devices(parent, null, 8)).failure.kind,
                )
            }
            withRepository(
                MockEngine { request ->
                    assertEquals(setupOwner.companyId, request.url.parameters["afterCompanyId"])
                    respond(page, headers = headers)
                }
            ) { repository ->
                assertIs<AppResult.Failed>(repository.devices(parent, setupOwner.companyId, 8))
            }
        }

    @Test
    fun admissionRejectsForeignScopeNegativeVersionsAndInvalidLifetimes() = runTest {
        val original = Json.parseToJsonElement(admission).jsonObject
        for ((key, value) in
            listOf(
                "accountId" to JsonPrimitive(SETUP_DEVICE),
                "companyId" to JsonPrimitive(SETUP_DEVICE),
                "nativeSessionId" to JsonPrimitive(SETUP_DEVICE),
                "deviceId" to JsonPrimitive("invalid"),
                "deviceVersion" to JsonPrimitive(-1),
                "nativeSessionVersion" to JsonPrimitive(-1),
                "credentialVersion" to JsonPrimitive(-1),
                "expiresAt" to JsonPrimitive("2026-11-02T00:01:01Z"),
                "expiresAt" to JsonPrimitive("2026-11-02T00:00:00Z"),
                "admittedAt" to JsonPrimitive("invalid"),
            )) withRepository(
            MockEngine {
                respond(JsonObject(original + (key to value)).toString(), headers = headers)
            }
        ) { repository ->
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.admission(setupOwner)).failure.kind,
            )
        }
    }

    @Test
    fun byteLimitsAndServerDenialArePreservedWithoutAnApplicationRetry() = runTest {
        for (directory in listOf(false, true)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond(" ".repeat(if (directory) 16_385 else 2049), headers = headers)
                }
            ) { repository ->
                assertIs<AppResult.Failed>(
                    if (directory) repository.devices(parent, null, 8)
                    else repository.admission(setupOwner)
                )
            }
            assertEquals(1, calls)
        }
        withRepository(
            MockEngine { respond("""{"code":"mfa_required"}""", HttpStatusCode.Forbidden, headers) }
        ) { repository ->
            val failure = assertIs<AppResult.Failed>(repository.admission(setupOwner)).failure
            assertEquals("mfa_required", failure.code)
            assertEquals(FailureKind.AccessDenied, failure.kind)
        }
    }

    @Test
    fun callerCancellationStopsPendingTransport() = runTest {
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
        ) { repository ->
            val pending = async { repository.admission(setupOwner) }
            entered.await()
            pending.cancelAndJoin()
            stopped.await()
            assertTrue(pending.isCancelled)
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        action: suspend (RemoteCollaborationRecoveryRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(parent.sessionId, session)
                        assertNull(rejected)
                        AppResult.Success("fictional-native")
                    }
                }
            }
        try {
            action(
                RemoteCollaborationRecoveryRepository(
                    ApiCollaborationRecoveryRemoteSource(CollaborationRecoveryApi(client)),
                    IdentityServer(SETUP_BASE, "Fictional Android"),
                )
            )
        } finally {
            client.close()
        }
    }
}
