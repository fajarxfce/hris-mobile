package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class CollaborationSdkRecordTest {
    private val endpoint = CollaborationSdkEndpoint("https://hris.example.test/")
    private val server = IdentityServer(SETUP_BASE, "Fictional phone")
    private val command =
        CollaborationSetupCommand(
            SETUP_OPERATION,
            setupOwner.setupBinding(),
            CollaborationNativePlatform.ANDROID,
            5,
            Instant.parse("2026-10-31T00:00:00Z"),
            CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, null),
        )
    private val gateway =
        Json.decodeFromString<CollaborationGatewayDto>(setupGatewayJson)
            .toGateway(setupOwner, setupBootstrap)
    private val passphrase = Base64.encode(ByteArray(32) { it.toByte() })

    @Test
    fun stableStoreIdentityExcludesLocalSessionAndSeparatesAllPermanentOwners() = runTest {
        val original = scope()
        assertEquals(
            "9f63ee387a743f8d6efb55e76a650e82a7ade9c533081cd37774abb54797b6d6",
            original.storeId,
        )
        assertTrue(original.credentialRecordName.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
        assertEquals(
            original.storeId,
            scope(setupOwner.copy(sessionId = "replacement-local")).storeId,
        )
        val identities =
            listOf(
                setupOwner.copy(accountId = "10000000-0000-4000-8000-000000000001"),
                setupOwner.copy(companyId = "20000000-0000-4000-8000-000000000001"),
                setupOwner.copy(nativeSessionId = "30000000-0000-4000-8000-000000000001"),
            )
        for (owner in identities) assertNotEquals(original.storeId, scope(owner).storeId)
        assertNotEquals(
            original.storeId,
            collaborationSdkScope(setupOwner, SETUP_JOB, server, endpoint).storeId,
        )
        assertNotEquals(
            original.storeId,
            collaborationSdkScope(
                    setupOwner,
                    SETUP_DEVICE,
                    server,
                    CollaborationSdkEndpoint("https://alternate.example.test/"),
                )
                .storeId,
        )
    }

    @Test
    fun rotationAndInitializationPreserveTheStorePassphraseAndBootstrapEvidence() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val initial = codec.create(command, gateway, passphrase)
        assertFalse(initial.initialized)
        val rotated =
            codec.withTokens(initial, "hris_mx_a_" + "c".repeat(43), "hris_mx_r_" + "d".repeat(43))
        val initialized = codec.initialized(rotated)
        assertEquals(passphrase, initialized.passphrase)
        assertEquals(SETUP_OPERATION, initialized.bootstrapOperationId)
        assertEquals(SETUP_GATEWAY, initialized.bootstrapGatewayId)
        assertEquals(initial.initialAccessExpiresAt, initialized.initialAccessExpiresAt)
        assertEquals(initial.expiresAt, initialized.expiresAt)
        assertEquals(2, initialized.credentialRevision)
        assertEquals(initialized, codec.initialized(initialized))
        assertEquals(
            initialized,
            codec.withTokens(initialized, initialized.accessToken, initialized.refreshToken),
        )
        assertEquals(initialized, codec.decode(codec.encode(initialized)))
        assertFalse(initialized.toString().contains(initialized.accessToken))
        assertFalse(initialized.toString().contains(passphrase))
    }

    @Test
    fun aReplacedLocalSessionCanReadTheSameOwnedRecordWithoutADeadlineCheck() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val retained =
            codec
                .create(command, gateway, passphrase)
                .copy(
                    initialAccessExpiresAt = "2026-01-01T00:00:00Z",
                    expiresAt = "2026-01-02T00:00:00Z",
                )
        val replacement =
            CollaborationSdkRecordCodec(scope(setupOwner.copy(sessionId = "restored")))
        assertEquals(retained, replacement.decode(codec.encode(retained)))
    }

    @Test
    fun alteredOwnersGatewayOriginAndMatrixIdentityCannotAuthenticateAsTheCurrentStore() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val initial = codec.create(command, gateway, passphrase)
        listOf(
                initial.copy(storeId = "a".repeat(64)),
                initial.copy(accountId = SETUP_DEVICE),
                initial.copy(companyId = SETUP_DEVICE),
                initial.copy(nativeSessionId = SETUP_DEVICE),
                initial.copy(audience = "https://other.example.test/"),
                initial.copy(deviceId = SETUP_JOB),
                initial.copy(homeserverUrl = "https://other.example.test/"),
                initial.copy(matrixUserId = "@foreign:matrix.example.test"),
                initial.copy(matrixDeviceId = "OTHER"),
            )
            .forEach {
                assertFailsWith<IllegalArgumentException> { codec.decode(Json.encodeToString(it)) }
            }
        assertEquals(initial, codec.decode(codec.encode(initial)))
    }

    @Test
    fun malformedSecretsSchemaAndRevisionBoundsAreRejectedWithoutNormalization() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val initial = codec.create(command, gateway, passphrase)
        listOf(
                initial.copy(schema = 2),
                initial.copy(passphrase = Base64.encode(ByteArray(31))),
                initial.copy(accessToken = "provider-access"),
                initial.copy(refreshToken = "native-refresh"),
                initial.copy(bootstrapOperationId = "not-an-operation"),
                initial.copy(bootstrapGatewayVersion = -1),
                initial.copy(credentialRevision = -1),
                initial.copy(bootstrapClientBuild = 0),
            )
            .forEach { assertFailsWith<IllegalArgumentException> { codec.encode(it) } }
        assertFailsWith<IllegalArgumentException> { codec.decode(" ".repeat(16_385)) }
        assertFailsWith<IllegalArgumentException> { codec.decode("{}") }
        assertFailsWith<IllegalArgumentException> {
            codec.withTokens(
                initial.copy(credentialRevision = 9_007_199_254_740_991L),
                "hris_mx_a_" + "c".repeat(43),
                "hris_mx_r_" + "d".repeat(43),
            )
        }
    }

    @Test
    fun endpointSelectionRequiresExplicitRootConfigurationAndMatchingApiAudience() = runTest {
        listOf(
                "http://hris.example.test/",
                "https://user:password@hris.example.test/",
                "https://hris.example.test/_matrix/",
                "https://hris.example.test/?query=1",
                "https://hris.example.test/#fragment",
            )
            .forEach { assertFailsWith<IllegalArgumentException> { CollaborationSdkEndpoint(it) } }
        assertEquals(
            "http://127.0.0.1:8008/",
            CollaborationSdkEndpoint("http://127.0.0.1:8008/", true).baseUrl,
        )
        assertFailsWith<IllegalArgumentException> {
            collaborationSdkScope(
                setupOwner,
                SETUP_DEVICE,
                IdentityServer("https://foreign.example.test/", "Fictional"),
                endpoint,
            )
        }
    }

    private suspend fun scope(owner: CollaborationOwner = setupOwner) =
        collaborationSdkScope(owner, SETUP_DEVICE, server, endpoint)
}
