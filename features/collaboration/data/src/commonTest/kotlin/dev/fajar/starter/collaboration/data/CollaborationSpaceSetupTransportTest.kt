package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.api.CollaborationSpaceSetupApi
import dev.fajar.starter.collaboration.data.datasources.ApiCollaborationSpaceSetupRemoteSource
import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceGatewayDto
import dev.fajar.starter.collaboration.data.repositories.RemoteCollaborationSpaceSetupRepository
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
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

class CollaborationSpaceSetupTransportTest {
    private val parent =
        CollaborationNativeParent(
            setupOwner.sessionId,
            setupOwner.audience,
            setupOwner.accountId,
            setupOwner.nativeSessionId,
        )
    private val owner = CollaborationSpaceOwner(parent, "70000000-0000-4000-8000-000000000001")
    private val binding =
        CollaborationSpaceDeviceBinding(
            parent.audience,
            parent.accountId,
            owner.spaceId,
            parent.nativeSessionId,
        )
    private val provider =
        CollaborationSpaceIdentity(
            owner.spaceId,
            parent.accountId,
            "matrix.example.test",
            setupUser,
            CollaborationSpaceIdentityOrigin.LEGACY,
            setupOwner.companyId,
            0,
            Instant.parse("2020-01-01T00:00:00Z"),
        )
    private val retry =
        CollaborationSpaceProvisioningRetry(SETUP_OPERATION, binding, SETUP_DEVICE, 2, 6)
    private val bootstrap =
        CollaborationSpaceBootstrap(
            SETUP_OPERATION,
            binding,
            CollaborationNativePlatform.ANDROID,
            5,
            SETUP_DEVICE,
            2,
            null,
            provider,
            "HRIS_${SETUP_DEVICE.replace("-", "")}",
        )
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val receipt = """{"id":"$SETUP_DEVICE","version":7}"""

    private fun gateway(command: CollaborationSpaceBootstrap = bootstrap) =
        CollaborationSpaceGatewayDto(
            command.binding.spaceId,
            command.binding.accountId,
            command.deviceId,
            command.binding.nativeSessionId,
            command.provider.matrixUserId,
            command.matrixDeviceId,
            SETUP_GATEWAY,
            (command.gatewayVersion ?: -1) + 1,
            "hris_mxg_a_" + "a".repeat(43),
            "hris_mxg_r_" + "b".repeat(43),
            "2026-11-05T00:05:00Z",
            "2026-11-06T00:00:00Z",
        )

    @Test
    fun retryKeepsOriginalNativeOperationBodyAndProvisioningRevisionAcrossOneRotation() = runTest {
        var calls = 0
        val bodies = mutableListOf<String>()
        withRepository(
            MockEngine { request ->
                assertRequest(request, "retry", 12)
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                val json = Json.parseToJsonElement(body).jsonObject
                assertEquals(
                    setOf("deviceId", "expectedDeviceVersion", "expectedProvisioningRevision"),
                    json.keys,
                )
                assertEquals(2L, json.getValue("expectedDeviceVersion").jsonPrimitive.long)
                assertEquals(6L, json.getValue("expectedProvisioningRevision").jsonPrimitive.long)
                assertEquals(SETUP_DEVICE, json.getValue("deviceId").jsonPrimitive.content)
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, headers)
                else respond(receipt, headers = headers)
            },
            number = 12,
        ) {
            assertEquals(
                AppResult.Success(CollaborationSetupReceipt(SETUP_DEVICE, 7)),
                it.retry(owner, retry),
            )
            assertEquals(2, calls)
            assertEquals(bodies.first(), bodies.last())
        }
    }

    @Test
    fun bootstrapKeepsActualBuildAndOriginalProviderIdentityAcrossOneRotation() = runTest {
        var calls = 0
        val bodies = mutableListOf<String>()
        withRepository(
            MockEngine { request ->
                assertRequest(request, "bootstrap")
                val body = request.body.toByteArray().decodeToString()
                bodies += body
                val json = Json.parseToJsonElement(body).jsonObject
                assertEquals(
                    setOf("deviceId", "expectedDeviceVersion", "expectedGatewayVersion"),
                    json.keys,
                )
                assertEquals(JsonNull, json.getValue("expectedGatewayVersion"))
                assertFalse(body.contains(setupUser))
                assertFalse(body.contains(parent.nativeSessionId))
                assertEquals(
                    if (calls == 0) "Bearer initial-native" else "Bearer renewed-native",
                    request.headers[HttpHeaders.Authorization],
                )
                if (calls++ == 0) respond("{}", HttpStatusCode.Unauthorized, headers)
                else respond(Json.encodeToString(gateway()), headers = headers)
            }
        ) {
            val result =
                assertIs<AppResult.Success<CollaborationSpaceGateway>>(
                        it.bootstrap(owner, bootstrap)
                    )
                    .value
            assertEquals(binding, result.binding)
            assertEquals(SETUP_DEVICE, result.deviceId)
            assertEquals(SETUP_GATEWAY, result.sessionId)
            assertEquals(0L, result.version)
            assertEquals(setupUser, result.matrixUserId)
            assertEquals(bootstrap.matrixDeviceId, result.matrixDeviceId)
            assertEquals(2, calls)
            assertEquals(bodies.first(), bodies.last())
            for (text in
                listOf(
                    result.toString(),
                    gateway().toString(),
                    bootstrap.toString(),
                    retry.toString(),
                )) {
                assertFalse(text.contains(result.accessToken))
                assertFalse(text.contains(result.refreshToken))
                assertFalse(text.contains(setupUser))
                assertFalse(text.contains(parent.accountId))
            }
        }
    }

    @Test
    fun renewedLocalHandleKeepsNativeFamilyButOtherAccountSpaceOrParentCannotSend() = runTest {
        val current = parent.copy(sessionId = "new-local")
        var calls = 0
        withRepository(
            MockEngine { request ->
                calls++
                respond(
                    if (request.url.encodedPath.endsWith("/retry")) receipt
                    else Json.encodeToString(gateway()),
                    headers = headers,
                )
            },
            currentParent = current,
        ) { repo ->
            val renewed = owner.copy(parent = current)
            assertIs<AppResult.Success<*>>(repo.retry(renewed, retry))
            assertIs<AppResult.Success<*>>(repo.bootstrap(renewed, bootstrap))
            for (foreign in
                listOf(
                    renewed.copy(parent = current.copy(nativeSessionId = SETUP_GATEWAY)),
                    renewed.copy(parent = current.copy(accountId = SETUP_GATEWAY)),
                    renewed.copy(parent = current.copy(audience = "https://other.example/")),
                    renewed.copy(parent = current.copy(sessionId = "bad session")),
                    renewed.copy(spaceId = SETUP_GATEWAY),
                    renewed.copy(spaceId = "../outside"),
                )) {
                assertIs<AppResult.Failed>(repo.retry(foreign, retry))
                assertIs<AppResult.Failed>(repo.bootstrap(foreign, bootstrap))
            }
            assertEquals(2, calls)
        }
    }

    @Test
    fun bootstrapNeverImpersonatesOldBuildOrPlatformAndIosUsesActualHeaders() = runTest {
        for ((platform, number) in
            listOf(
                AppPlatform.Android to 6L,
                AppPlatform.Ios to 5L,
                AppPlatform.Web to 5L,
                AppPlatform.Desktop to 5L,
            )) {
            withRepository(
                MockEngine { error("Mismatched bootstrap reached HTTP") },
                platform = platform,
                number = number,
            ) {
                assertIs<AppResult.Failed>(it.bootstrap(owner, bootstrap))
            }
        }
        val input = bootstrap.copy(platform = CollaborationNativePlatform.IOS)
        withRepository(
            MockEngine { request ->
                assertRequest(request, "bootstrap", platform = "IOS")
                respond(Json.encodeToString(gateway(input)), headers = headers)
            },
            platform = AppPlatform.Ios,
        ) {
            assertIs<AppResult.Success<*>>(it.bootstrap(owner, input))
        }
    }

    @Test
    fun userOriginAndDeviceOriginStayIndependentWithoutChangingProviderOrEndpoint() = runTest {
        val spaceDevice = "HRISG_${SETUP_DEVICE.replace("-", "")}"
        val spaceProvider =
            provider.copy(
                origin = CollaborationSpaceIdentityOrigin.SPACE,
                legacyCompanyId = null,
                matrixUserId =
                    "@hrisg_${owner.spaceId.replace("-", "")}_${parent.accountId.replace("-", "")}:${provider.serverName}",
            )
        for (input in
            listOf(
                bootstrap.copy(matrixDeviceId = spaceDevice),
                bootstrap.copy(provider = spaceProvider, matrixDeviceId = spaceDevice),
            )) {
            withRepository(
                MockEngine { request ->
                    assertEquals("hris.example.test", request.url.host)
                    respond(Json.encodeToString(gateway(input)), headers = headers)
                }
            ) {
                val value =
                    assertIs<AppResult.Success<CollaborationSpaceGateway>>(
                            it.bootstrap(owner, input)
                        )
                        .value
                assertEquals(input.provider.matrixUserId, value.matrixUserId)
                assertEquals(spaceDevice, value.matrixDeviceId)
            }
        }
        withRepository(MockEngine { error("Malformed provider identity reached HTTP") }) { repo ->
            for (input in
                listOf(
                    bootstrap.copy(provider = spaceProvider),
                    bootstrap.copy(
                        provider = provider.copy(matrixUserId = "@other:matrix.example.test")
                    ),
                    bootstrap.copy(
                        provider =
                            provider.copy(
                                serverName = "matrix.example.test:0",
                                matrixUserId = setupUser + ":0",
                            )
                    ),
                    bootstrap.copy(matrixDeviceId = "HRISG_${SETUP_GATEWAY.replace("-", "")}"),
                    bootstrap.copy(matrixDeviceId = "HRIS_${"A".repeat(32)}"),
                )) assertIs<AppResult.Failed>(repo.bootstrap(owner, input))
        }
    }

    @Test
    fun requestBoundsRejectBeforeHttpAndFinalSupportedGatewayVersionMaps() = runTest {
        withRepository(MockEngine { error("Malformed setup request reached HTTP") }) { repo ->
            for (input in
                listOf(
                    retry.copy(operationId = "bad"),
                    retry.copy(deviceId = "bad"),
                    retry.copy(deviceVersion = -1),
                    retry.copy(deviceVersion = 999_999),
                    retry.copy(provisioningRevision = -1),
                    retry.copy(provisioningRevision = 999_999),
                    retry.copy(binding = binding.copy(nativeSessionId = SETUP_GATEWAY)),
                )) assertIs<AppResult.Failed>(repo.retry(owner, input))
            for (input in
                listOf(
                    bootstrap.copy(operationId = "bad"),
                    bootstrap.copy(deviceId = "bad"),
                    bootstrap.copy(deviceVersion = -1),
                    bootstrap.copy(deviceVersion = 1_000_000),
                    bootstrap.copy(gatewayVersion = -1),
                    bootstrap.copy(gatewayVersion = 999_999),
                    bootstrap.copy(clientBuild = 0),
                    bootstrap.copy(clientBuild = 1_000_000_000),
                    bootstrap.copy(binding = binding.copy(spaceId = SETUP_GATEWAY)),
                )) assertIs<AppResult.Failed>(repo.bootstrap(owner, input))
        }
        val last = bootstrap.copy(deviceVersion = 999_999, gatewayVersion = 999_998)
        withRepository(
            MockEngine { respond(Json.encodeToString(gateway(last)), headers = headers) }
        ) {
            assertEquals(
                999_999L,
                assertIs<AppResult.Success<CollaborationSpaceGateway>>(it.bootstrap(owner, last))
                    .value
                    .version,
            )
        }
    }

    @Test
    fun retryReceiptMustMatchExactDeviceAndIncrementedProvisioningRevision() = runTest {
        for (body in
            listOf(
                "{}",
                "x".repeat(1025),
                receipt.replace(SETUP_DEVICE, SETUP_GATEWAY),
                receipt.replace("\"version\":7", "\"version\":2"),
                receipt.replace("\"version\":7", "\"version\":1000000"),
            )) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond(body, headers = headers)
                }
            ) {
                assertIs<AppResult.Failed>(it.retry(owner, retry))
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun malformedGatewayNeverSubstitutesNativeOwnerProviderKeysOrTokenNamespace() = runTest {
        val valid = gateway()
        val invalid =
            listOf(
                    valid.copy(spaceId = SETUP_GATEWAY),
                    valid.copy(accountId = SETUP_GATEWAY),
                    valid.copy(nativeSessionId = SETUP_GATEWAY),
                    valid.copy(deviceId = SETUP_GATEWAY),
                    valid.copy(matrixUserId = "@other:matrix.example.test"),
                    valid.copy(matrixDeviceId = "OTHER"),
                    valid.copy(sessionId = "bad"),
                    valid.copy(version = -1),
                    valid.copy(version = 1),
                    valid.copy(version = 1_000_000),
                    valid.copy(accessToken = setupAccess),
                    valid.copy(refreshToken = setupRefresh),
                    valid.copy(accessToken = "hris_mxg_a_" + "a".repeat(42)),
                    valid.copy(refreshToken = "hris_mxg_r_" + "b".repeat(44)),
                    valid.copy(accessToken = valid.accessToken.dropLast(1) + "+"),
                    valid.copy(accessExpiresAt = "1960-01-01T00:00:00Z"),
                    valid.copy(expiresAt = "2026-11-05T00:00:00Z"),
                    valid.copy(accessExpiresAt = "invalid"),
                    valid.copy(expiresAt = "+10000-01-01T00:00:00Z"),
                )
                .map { Json.encodeToString(it) } + listOf("{}", "x".repeat(4097))
        for (body in invalid) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond(body, headers = headers)
                }
            ) {
                assertIs<AppResult.Failed>(it.bootstrap(owner, bootstrap))
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun oldCredentialDatesRemainTypedMetadataWithoutGrantingLiveSdkAuthority() = runTest {
        val old =
            gateway()
                .copy(accessExpiresAt = "2020-01-01T00:05:00Z", expiresAt = "2020-01-02T00:00:00Z")
        withRepository(MockEngine { respond(Json.encodeToString(old), headers = headers) }) {
            val received =
                assertIs<AppResult.Success<CollaborationSpaceGateway>>(
                        it.bootstrap(owner, bootstrap)
                    )
                    .value
            assertEquals(
                Instant.parse(old.expiresAt).toEpochMilliseconds(),
                received.expiresAtEpochMillis,
            )
        }
    }

    @Test
    fun deniedAndSecondUnauthorizedResponsesStayClassifiedWithNoAutomaticSetupRetry() = runTest {
        for (isBootstrap in listOf(false, true)) for (status in
            listOf(HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized)) {
            var calls = 0
            withRepository(
                MockEngine {
                    calls++
                    respond("""{"code":"collaboration_access_denied"}""", status, headers)
                }
            ) {
                val result =
                    if (isBootstrap) it.bootstrap(owner, bootstrap) else it.retry(owner, retry)
                val failure = assertIs<AppResult.Failed>(result).failure
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
    fun cancellationStopsBothPendingNativeRequestsAndDisposesTransport() = runTest {
        for (isBootstrap in listOf(false, true)) {
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
            ) {
                val task = async {
                    if (isBootstrap) it.bootstrap(owner, bootstrap) else it.retry(owner, retry)
                }
                entered.await()
                task.cancelAndJoin()
                stopped.await()
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun cancelledLateGatewayCannotPublishCredentials() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var published = false
        var calls = 0
        withRepository(
            MockEngine {
                calls++
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                respond(Json.encodeToString(gateway()), headers = headers)
            }
        ) {
            val task = launch {
                it.bootstrap(owner, bootstrap)
                published = true
            }
            entered.await()
            task.cancel()
            release.complete(Unit)
            task.join()
            assertTrue(task.isCancelled)
            assertFalse(published)
            assertEquals(1, calls)
        }
    }

    private fun assertRequest(
        request: io.ktor.client.request.HttpRequestData,
        suffix: String,
        number: Long = 5,
        platform: String = "ANDROID",
    ) {
        assertEquals(HttpMethod.Post, request.method)
        assertEquals(
            "/api/v1/collaboration/spaces/${owner.spaceId}/native-device/$suffix",
            request.url.encodedPath,
        )
        assertEquals(SETUP_OPERATION, request.headers["Idempotency-Key"])
        assertEquals(platform, request.headers["X-HRIS-Client-Platform"])
        assertEquals(number.toString(), request.headers["X-HRIS-Client-Build"])
        assertNull(request.headers[HttpHeaders.Cookie])
        assertNull(request.headers["X-Company-Id"])
        assertTrue(request.url.parameters.isEmpty())
    }

    private suspend fun withRepository(
        engine: MockEngine,
        currentParent: CollaborationNativeParent = parent,
        number: Long = 5,
        platform: AppPlatform = AppPlatform.Android,
        action: suspend (RemoteCollaborationSpaceSetupRepository) -> Unit,
    ) {
        val build = AppBuild(platform, number, "fixture", null)
        val client =
            createHttpClient(engine, HttpClientSettings(SETUP_BASE)) {
                defaultRequest {
                    header("X-HRIS-Client-Platform", platform.name.uppercase())
                    header("X-HRIS-Client-Build", number)
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
                RemoteCollaborationSpaceSetupRepository(
                    ApiCollaborationSpaceSetupRemoteSource(CollaborationSpaceSetupApi(client)),
                    IdentityServer(SETUP_BASE, "Fixture"),
                    build,
                )
            )
        } finally {
            client.close()
        }
    }
}
