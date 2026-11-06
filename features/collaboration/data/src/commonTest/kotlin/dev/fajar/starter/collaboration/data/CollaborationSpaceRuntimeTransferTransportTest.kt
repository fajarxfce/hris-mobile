package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceRuntimeTransferApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceRuntimeTransferRemoteSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceRuntimeTransferRepository
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

class CollaborationSpaceRuntimeTransferTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val target = "90000000-0000-4000-8000-000000000001"
    private val transfer =
        CollaborationSpaceRuntimeTransfer(
            CollaborationSpaceDeviceBinding(
                parent.audience,
                parent.accountId,
                owner.spaceId,
                parent.nativeSessionId,
            ),
            CollaborationSpaceLegacySdk(
                setupOwner.setupBinding(),
                SETUP_DEVICE,
                CollaborationLocalDeviceIdentity(
                    setupUser,
                    "HRIS_${SETUP_DEVICE.replace("-", "")}",
                    "a".repeat(64),
                ),
            ),
            CollaborationRuntimeHandoverIntent(
                SETUP_OPERATION,
                owner.spaceId,
                target,
                2,
                "a".repeat(64),
            ),
        )
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val receipt = """{"id":"$SETUP_DEVICE","version":1}"""
    private val drained =
        CollaborationLegacyRuntimeDrainDto(
            CollaborationLegacyRuntimeTargetDto(owner.spaceId, target),
            1,
            0,
            null,
            "2026-11-06T12:00:00Z",
        )

    @Test
    fun freezeRetainsOriginalOperationAndCurrentHeadersAcrossOneNativeRotation() = runTest {
        val bodies = mutableListOf<String>()
        withRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(
                    "/api/v1/collaboration/spaces/${owner.spaceId}/native-device/legacy-runtime/freeze",
                    request.url.encodedPath,
                )
                assertEquals(SETUP_OPERATION, request.headers["Idempotency-Key"])
                assertEquals("ANDROID", request.headers["X-HRIS-Client-Platform"])
                assertEquals("57", request.headers["X-HRIS-Client-Build"])
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                val json = Json.parseToJsonElement(body).jsonObject
                assertEquals(setOf("deviceId", "expectedDeviceVersion", "fingerprint"), json.keys)
                assertEquals(target, json.getValue("deviceId").jsonPrimitive.content)
                assertEquals(2L, json.getValue("expectedDeviceVersion").jsonPrimitive.long)
                assertEquals(
                    transfer.intent.fingerprint,
                    json.getValue("fingerprint").jsonPrimitive.content,
                )
                assertFalse(body.contains(setupUser))
                assertFalse(body.contains("passphrase"))
                if (bodies.size == 1) respond("{}", HttpStatusCode.Unauthorized, headers)
                else respond(receipt, headers = headers)
            }
        ) { repo ->
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(SETUP_DEVICE, 1)),
                repo.freeze(owner, transfer),
            )
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
        }
    }

    @Test
    fun drainIsOneBoundedGetAndZeroDoesNotBecomeACompletionReceipt() = runTest {
        for (value in
            listOf(
                drained,
                drained.copy(activeRequests = 8, latestRequestExpiresAt = "2026-11-06T12:01:00Z"),
            )) {
            var calls = 0
            withRepository(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals(
                        "/api/v1/collaboration/spaces/${owner.spaceId}/native-device/legacy-runtime/drain",
                        request.url.encodedPath,
                    )
                    assertEquals(
                        setOf("deviceId", "expectedDeviceVersion", "fingerprint"),
                        request.url.parameters.names(),
                    )
                    assertEquals(target, request.url.parameters["deviceId"])
                    assertEquals("2", request.url.parameters["expectedDeviceVersion"])
                    assertEquals(transfer.intent.fingerprint, request.url.parameters["fingerprint"])
                    assertNull(request.headers["Idempotency-Key"])
                    respond(Json.encodeToString(value), headers = headers)
                }
            ) { repo ->
                val result =
                    assertIs<AppResult.Success<CollaborationSpaceRuntimeDrain>>(
                            repo.drain(owner, transfer)
                        )
                        .value
                assertEquals(target, result.deviceId)
                assertEquals(1L, result.runtimeVersion)
                assertEquals(value.activeRequests, result.activeRequests)
                assertEquals(Instant.parse(value.observedAt), result.observedAt)
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun foreignBindingParentFingerprintAndInvalidVersionNeverReachTransport() = runTest {
        val values =
            listOf(
                transfer.copy(binding = transfer.binding.copy(accountId = target)),
                transfer.copy(binding = transfer.binding.copy(spaceId = target)),
                transfer.copy(
                    legacy =
                        transfer.legacy.copy(
                            binding = transfer.legacy.binding.copy(nativeSessionId = target)
                        )
                ),
                transfer.copy(
                    legacy =
                        transfer.legacy.copy(
                            binding = transfer.legacy.binding.copy(audience = "foreign")
                        )
                ),
                transfer.copy(intent = transfer.intent.copy(spaceId = target)),
                transfer.copy(intent = transfer.intent.copy(expectedDeviceVersion = 999_999)),
                transfer.copy(intent = transfer.intent.copy(fingerprint = "b".repeat(64))),
                transfer.copy(intent = transfer.intent.copy(operationId = "../escape")),
            )
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                respond(receipt, headers = headers)
            }
        ) { repo ->
            for (value in values) {
                assertIs<AppResult.Failed>(repo.freeze(owner, value))
                assertIs<AppResult.Failed>(repo.drain(owner, value))
            }
            assertEquals(0, calls)
        }
    }

    @Test
    fun freezeCannotAcceptATargetDeviceReceiptOrAChangedRuntimeRevision() = runTest {
        for (body in
            listOf(
                """{"id":"$target","version":1}""",
                """{"id":"$SETUP_DEVICE","version":2}""",
                "{}",
                " ".repeat(1025) + receipt,
            )) {
            withRepository(MockEngine { respond(body, headers = headers) }) { repo ->
                assertIs<AppResult.Failed>(repo.freeze(owner, transfer))
            }
        }
    }

    @Test
    fun malformedDrainCoordinatesCountsAndLeaseTimesAreRejected() = runTest {
        for (value in
            listOf(
                drained.copy(target = drained.target.copy(deviceId = SETUP_DEVICE)),
                drained.copy(target = drained.target.copy(spaceId = target)),
                drained.copy(runtimeVersion = 0),
                drained.copy(activeRequests = -1),
                drained.copy(activeRequests = 9, latestRequestExpiresAt = "2026-11-06T12:00:10Z"),
                drained.copy(activeRequests = 1),
                drained.copy(latestRequestExpiresAt = "2026-11-06T12:00:10Z"),
                drained.copy(activeRequests = 1, latestRequestExpiresAt = drained.observedAt),
                drained.copy(
                    activeRequests = 1,
                    latestRequestExpiresAt = "2026-11-06T12:01:00.001Z",
                ),
                drained.copy(observedAt = "1969-01-01T00:00:00Z"),
            )) {
            withRepository(MockEngine { respond(Json.encodeToString(value), headers = headers) }) {
                repo ->
                assertIs<AppResult.Failed>(repo.drain(owner, transfer))
            }
        }
    }

    @Test
    fun deniedAndRepeatedUnauthorizedResponsesKeepTheirClassificationAndStop() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond("""{"code":"collaboration_access_denied"}""", status, headers)
                }
            ) { repo ->
                val result = assertIs<AppResult.Failed>(repo.freeze(owner, transfer))
                assertEquals(
                    if (status == HttpStatusCode.Forbidden) FailureKind.AccessDenied
                    else FailureKind.Unauthorized,
                    result.failure.kind,
                )
                assertEquals(if (status == HttpStatusCode.Forbidden) 1 else 2, calls)
            }
        }
    }

    @Test
    fun cancellationStopsPendingFreezeAndDrainWithoutAnotherRequest() = runTest {
        for (freeze in listOf(true, false)) {
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
                val pending = async {
                    if (freeze) repo.freeze(owner, transfer) else repo.drain(owner, transfer)
                }
                entered.await()
                pending.cancelAndJoin()
                stopped.await()
                assertEquals(1, calls)
            }
        }
    }

    private suspend fun withRepository(
        engine: MockEngine,
        action: suspend (RemoteCollaborationSpaceRuntimeTransferRepository) -> Unit,
    ) {
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                defaultRequest {
                    header("X-HRIS-Client-Platform", "ANDROID")
                    header("X-HRIS-Client-Build", 57)
                }
                install(SessionAuthentication) {
                    origin = Url(SETUP_BASE)
                    acquire = { session, rejected ->
                        assertEquals(parent.sessionId, session)
                        AppResult.Success(
                            if (rejected == null) "initial-native" else "renewed-native"
                        )
                    }
                }
            }
        try {
            action(
                RemoteCollaborationSpaceRuntimeTransferRepository(
                    ApiCollaborationSpaceRuntimeTransferRemoteSource(
                        CollaborationSpaceRuntimeTransferApi(client)
                    ),
                    IdentityServer(SETUP_BASE, "Fixture"),
                )
            )
        } finally {
            client.close()
        }
    }
}
