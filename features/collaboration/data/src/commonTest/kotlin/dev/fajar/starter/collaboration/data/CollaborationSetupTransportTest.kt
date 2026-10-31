package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CollaborationSetupTransportTest {
    @Test
    fun statusPreservesExpiredOwnedMetadataAndDistinguishesMissingRegistration() = runTest {
        var calls = 0
        withSetupRepository(
            MockEngine { request ->
                assertEquals(
                    "/api/v1/companies/${setupOwner.companyId}/collaboration/native-device",
                    request.url.encodedPath,
                )
                assertEquals(HttpMethod.Get, request.method)
                assertEquals(
                    "Bearer fictional-old-native",
                    request.headers[HttpHeaders.Authorization],
                )
                respond(
                    if (calls++ == 0) setupDeviceJson else "{\"device\":null}",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        ) { repository ->
            val device =
                assertIs<AppResult.Success<CollaborationDeviceSetup?>>(repository.read(setupOwner))
                    .value!!
            assertEquals(CollaborationDeviceState.ACTIVE, device.state)
            assertEquals(CollaborationProvisioningState.SUCCEEDED, device.provisioning?.state)
            assertNotNull(device.gateway)
            assertEquals(0, device.gateway?.version)
            assertEquals(AppResult.Success(null), repository.read(setupOwner))
        }
    }

    @Test
    fun registrationAndExplicitProvisioningRetryKeepOperationAndObservedVersions() = runTest {
        var calls = 0
        withSetupRepository(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(SETUP_OPERATION, request.headers["Idempotency-Key"])
                val body =
                    Json.parseToJsonElement(
                            (request.body as OutgoingContent.ByteArrayContent)
                                .bytes()
                                .decodeToString()
                        )
                        .jsonObject
                if (calls++ == 0) {
                    assertEquals(setOf("platform", "label"), body.keys)
                    assertEquals("ANDROID", body.getValue("platform").jsonPrimitive.content)
                    respond(
                        "{\"id\":\"$SETUP_DEVICE\",\"version\":0}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    assertTrue(request.url.encodedPath.endsWith("/retry"))
                    assertEquals(
                        setOf("deviceId", "expectedDeviceVersion", "expectedProvisioningVersion"),
                        body.keys,
                    )
                    assertEquals(4, body.getValue("expectedProvisioningVersion").jsonPrimitive.long)
                    // Retry returns its new job receipt, not the device identity.
                    respond(
                        "{\"id\":\"$SETUP_JOB\",\"version\":0}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        ) { repository ->
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(SETUP_DEVICE, 0)),
                repository.register(
                    setupOwner,
                    CollaborationRegistration(
                        SETUP_OPERATION,
                        CollaborationNativePlatform.ANDROID,
                        "Fictional Android",
                    ),
                ),
            )
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(SETUP_JOB, 0)),
                repository.retry(
                    setupOwner,
                    CollaborationProvisioningRetry(SETUP_OPERATION, SETUP_DEVICE, 0, 4),
                ),
            )
        }
    }

    @Test
    fun bootstrapReplaysTheSameOperationThroughOrdinaryNativeRefreshAndRedactsCredentials() =
        runTest {
            val requests = mutableListOf<String>()
            withSetupRepository(
                MockEngine { request ->
                    assertTrue(request.url.encodedPath.endsWith("/bootstrap"))
                    assertEquals(SETUP_OPERATION, request.headers["Idempotency-Key"])
                    requests +=
                        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    if (requests.size == 1)
                        respond(
                            "{\"code\":\"authentication_required\"}",
                            HttpStatusCode.Unauthorized,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                    else {
                        assertEquals(
                            "Bearer fictional-renewed-native",
                            request.headers[HttpHeaders.Authorization],
                        )
                        respond(
                            setupGatewayJson,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            ) { repository ->
                val result =
                    assertIs<AppResult.Success<CollaborationGateway>>(
                        repository.bootstrap(setupOwner, setupBootstrap)
                    )
                assertEquals(setupUser, result.value.matrixUserId)
                assertEquals(SETUP_DEVICE, result.value.deviceId)
                assertEquals(setupAccess, result.value.accessToken)
                assertEquals(setupRefresh, result.value.refreshToken)
                assertFalse(result.toString().contains(setupAccess))
                assertEquals(2, requests.size)
                assertEquals(requests[0], requests[1])
                val request = Json.parseToJsonElement(requests[0]).jsonObject
                assertEquals(JsonNull, request["expectedGatewayVersion"])
                assertEquals(1, request.getValue("expectedDeviceVersion").jsonPrimitive.long)
            }
        }

    @Test
    fun admissionErrorsKeepCodesWithoutAdapterRetriesAndCancellationPropagates() = runTest {
        var calls = 0
        withSetupRepository(
            MockEngine {
                calls++
                if (calls == 1)
                    respond(
                        "{\"code\":\"collaboration_unavailable\",\"detail\":\"private-fixture\"}",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                else throw CancellationException("owned fixture")
            }
        ) { repository ->
            val failure = assertIs<AppResult.Failed>(repository.read(setupOwner)).failure
            assertEquals("collaboration_unavailable", failure.code)
            assertFalse(failure.message.contains("private-fixture"))
            assertEquals(1, calls)
            assertFailsWith<CancellationException> { repository.read(setupOwner) }
        }
    }
}
