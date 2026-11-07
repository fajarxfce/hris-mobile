package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.toGateway
import dev.fajar.starter.collaboration.data.repositories.SdkInstalledGateway
import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class CollaborationSdkRecordTest {
    @Test
    fun credentialReplacementKeepsQueueNamespaceWhileAdmissionUsesTheNewOperation() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        for (legacy in listOf(false, true)) {
            val original =
                codec.initialized(codec.create(command, gateway, passphrase)).let {
                    if (legacy) it.copy(queueOperationId = null) else it
                }
            val replaced =
                codec.replaceCredentials(
                    codec.decode(codec.encode(original)),
                    command.copy(
                        id = SETUP_JOB,
                        action =
                            CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, gateway.version),
                    ),
                    renewedGateway(),
                )
            assertEquals(SETUP_OPERATION, replaced.messageOperationId)
            assertEquals(SETUP_JOB, replaced.bootstrapOperationId)
            assertEquals(original.passphrase, replaced.passphrase)
            assertEquals(original.storeId, replaced.storeId)
            assertEquals(original.matrixDeviceId, replaced.matrixDeviceId)
            assertEquals(original.credentialRevision + 1, replaced.credentialRevision)
            assertFalse(replaced.initialized)
            assertEquals(replaced, codec.decode(codec.encode(replaced)))
            val again =
                codec.replaceCredentials(
                    replaced,
                    command.copy(
                        action =
                            CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, gateway.version + 1)
                    ),
                    renewedGateway(version = gateway.version + 2),
                )
            assertEquals(SETUP_OPERATION, again.messageOperationId)
        }
    }

    @Test
    fun replacementRejectsForeignOrStaleStoreIdentityAndMalformedQueueAnchors() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val original = codec.create(command, gateway, passphrase)
        for (changed in
            listOf(
                gateway,
                renewedGateway(sessionId = SETUP_JOB),
                renewedGateway(matrixDeviceId = "FOREIGN"),
                renewedGateway(matrixUserId = "@foreign:fixture.test"),
            )) {
            assertFailsWith<IllegalArgumentException> {
                codec.replaceCredentials(
                    original,
                    command.copy(
                        id = SETUP_JOB,
                        action =
                            CollaborationSetupAction.Bootstrap(SETUP_DEVICE, 1, gateway.version),
                    ),
                    changed,
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encode(original.copy(queueOperationId = "bad"))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.replaceCredentials(original, command, renewedGateway())
        }
    }

    private val handover =
        CollaborationRuntimeHandoverIntent(
            "a0000000-0000-0000-0000-000000000001",
            "a0000000-0000-0000-0000-000000000002",
            "a0000000-0000-0000-0000-000000000003",
            0,
            "a".repeat(64),
        )

    @Test
    fun durableHandoverPreservesOriginalStoreAndRejectsRefreshOrRetargeting() = runTest {
        val originalScope = scope()
        val codec = CollaborationSdkRecordCodec(originalScope)
        val original = codec.initialized(codec.create(command, gateway, passphrase))
        assertFalse(codec.encode(original).contains("handover"))
        val staged = codec.stageHandover(original, handover)
        assertEquals(2, staged.schema)
        assertEquals(original.credentialRevision + 1, staged.credentialRevision)
        assertEquals(original.storeId, staged.storeId)
        assertEquals(original.companyId, staged.companyId)
        assertEquals(original.nativeSessionId, staged.nativeSessionId)
        assertEquals(original.passphrase, staged.passphrase)
        assertEquals(original.accessToken, staged.accessToken)
        assertEquals(staged, codec.stageHandover(staged, handover))
        val restored = CollaborationSdkRecordCodec(scope(setupOwner.copy(sessionId = "restored")))
        val value = restored.decode(codec.encode(staged))
        assertEquals(handover, restored.handoverIntent(value))
        for (changed in
            listOf(
                handover.copy(operationId = SETUP_OPERATION),
                handover.copy(spaceId = SETUP_OPERATION),
                handover.copy(deviceId = SETUP_DEVICE),
                handover.copy(expectedDeviceVersion = 1),
                handover.copy(fingerprint = "b".repeat(64)),
            )) assertFailsWith<IllegalArgumentException> { codec.stageHandover(staged, changed) }
        assertFailsWith<IllegalArgumentException> { codec.initialized(staged) }
        assertFailsWith<IllegalArgumentException> {
            codec.withTokens(staged, staged.accessToken, staged.refreshToken)
        }
        assertEquals(handover, codec.handoverIntent(codec.decode(codec.encode(staged))))
    }

    @Test
    fun incompleteOrMalformedHandoverCannotBecomeAValidProtectedRecord() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val empty = codec.create(command, gateway, passphrase)
        assertFailsWith<IllegalArgumentException> { codec.stageHandover(empty, handover) }
        val original = codec.initialized(empty)
        for (invalid in
            listOf(
                handover.copy(operationId = "invalid"),
                handover.copy(spaceId = handover.spaceId.uppercase()),
                handover.copy(expectedDeviceVersion = -1),
                handover.copy(expectedDeviceVersion = 999_999),
                handover.copy(fingerprint = "A".repeat(64)),
            )) assertFailsWith<IllegalArgumentException> { codec.stageHandover(original, invalid) }
        assertFailsWith<IllegalArgumentException> {
            codec.stageHandover(
                original.copy(credentialRevision = 9_007_199_254_740_991L),
                handover,
            )
        }
        val staged = codec.stageHandover(original, handover)
        for (invalid in
            listOf(
                staged.copy(schema = 1),
                staged.copy(handover = null),
                staged.copy(initialized = false),
                staged.copy(credentialRevision = 0),
            )) assertFailsWith<IllegalArgumentException> {
            codec.decode(Json.encodeToString(invalid))
        }
    }

    @Test
    fun uninitializedHandoverRecordRetainsItsDistinctSchemaAndRejectsLegacyCredentials() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val original = codec.initialized(codec.create(command, gateway, passphrase))
        val held = codec.stageHandover(original, handover).copy(schema = 3, initialized = false)
        val restored = codec.decode(Json.encodeToString(held))
        assertFalse(restored.initialized)
        assertEquals(held, restored)
        assertEquals(handover, codec.handoverIntent(restored))
        assertEquals(held, codec.decode(codec.encode(restored)))
        assertEquals(original.messageOperationId, restored.messageOperationId)
        assertFailsWith<IllegalArgumentException> { codec.initialized(restored) }
        assertFailsWith<IllegalArgumentException> {
            codec.withTokens(restored, restored.accessToken, restored.refreshToken)
        }
        assertFailsWith<IllegalArgumentException> {
            codec.replaceCredentials(restored, command.copy(id = SETUP_JOB), renewedGateway())
        }
        assertFailsWith<IllegalArgumentException> { codec.stageHandover(restored, handover) }
    }

    @Test
    fun uninitializedHandoverFormatCannotClaimInitializationOrLoseItsTarget() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val original = codec.initialized(codec.create(command, gateway, passphrase))
        val held = codec.stageHandover(original, handover).copy(schema = 3, initialized = false)
        for (invalid in
            listOf(
                held.copy(initialized = true),
                held.copy(handover = null),
                held.copy(credentialRevision = 0),
                held.copy(schema = 2),
                held.copy(schema = 4),
            )) assertFailsWith<IllegalArgumentException> {
            codec.decode(Json.encodeToString(invalid))
        }
    }

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
    fun replacementRequiresANewerBootstrapOnTheOriginalProviderDeviceAndGateway() = runTest {
        val installed =
            SdkInstalledGateway(
                CollaborationSdkRecordCodec(scope()).create(command, gateway, passphrase)
            )
        val next = command.copy(id = SETUP_JOB)
        val renewed = renewedGateway()
        assertTrue(installed.acceptsReplacement(next, renewed))
        assertFalse(installed.acceptsReplacement(command, renewed))
        for (other in
            listOf(
                gateway,
                renewedGateway(sessionId = SETUP_JOB),
                renewedGateway(matrixUserId = "@foreign:fixture.test"),
                renewedGateway(matrixDeviceId = "FOREIGN"),
            )) assertFalse(installed.acceptsReplacement(next, other))
    }

    @Test
    fun refreshedTokenPersistenceDoesNotChangeOriginalBootstrapIdentity() = runTest {
        val codec = CollaborationSdkRecordCodec(scope())
        val restored =
            codec
                .create(command, gateway, passphrase)
                .copy(
                    accessToken = "fixture-refreshed-access",
                    refreshToken = "fixture-refreshed-refresh",
                    initialized = true,
                    credentialRevision = 7,
                )
        val installed = SdkInstalledGateway(restored)
        assertTrue(
            installed.acceptsReplacement(
                command.copy(id = SETUP_JOB),
                renewedGateway(version = gateway.version + 8),
            )
        )
        assertFalse(installed.acceptsReplacement(command.copy(id = SETUP_JOB), gateway))
    }

    private fun renewedGateway(
        version: Long = gateway.version + 1,
        sessionId: String = gateway.sessionId,
        matrixUserId: String = gateway.matrixUserId,
        matrixDeviceId: String = gateway.matrixDeviceId,
    ) =
        CollaborationGateway(
            gateway.owner,
            gateway.deviceId,
            sessionId,
            version,
            matrixUserId,
            matrixDeviceId,
            "hris_mx_a_" + "e".repeat(43),
            "hris_mx_r_" + "f".repeat(43),
            gateway.accessExpiresAtEpochMillis,
            gateway.expiresAtEpochMillis,
        )

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
