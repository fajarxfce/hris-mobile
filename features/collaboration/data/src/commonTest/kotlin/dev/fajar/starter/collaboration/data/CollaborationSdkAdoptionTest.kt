package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.sdk.*
import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationSdkAdoptionTest {
    @Test
    fun admittedAdoptedReplacementClearsOnlyItsOwnRejection() = runTest {
        val f = SdkAdoptionFixture.create()
        val original = f.adopted.copy(authenticationRejected = true)
        val rejected = f.codec.rejectAdoptedCredentials(original, f.command.id)
        assertEquals(rejected, f.codec.rejectAdoptedCredentials(rejected, f.command.id))
        val gateway = f.gateway.copy(version = 1)
        val command =
            f.command.copy(request = f.bootstrap.copy(operationId = SETUP_JOB, gatewayVersion = 0))
        val replaced = f.codec.replaceAdoptedCredentials(rejected, command, gateway)
        assertTrue(replaced.authenticationRejected)
        assertFalse(assertNotNull(replaced.adoption).authenticationRejected)
        assertEquals(original.passphrase, replaced.passphrase)
        assertEquals(original.messageOperationId, replaced.messageOperationId)
    }

    @Test
    fun initializedAndInterruptedOriginalsKeepEveryOriginalFieldAndQueueIdentity() = runTest {
        val f = SdkAdoptionFixture.create()
        for (initialized in listOf(false, true)) {
            val original = if (initialized) f.codec.initialized(f.original) else f.original
            val staged = f.codec.stageExistingHandover(original, f.target)
            val adopted = f.codec.stageAdoption(staged, f.command, f.gateway)
            assertEquals(4, adopted.schema)
            assertEquals(
                staged,
                adopted.copy(
                    schema = staged.schema,
                    credentialRevision = staged.credentialRevision,
                    adoption = null,
                ),
            )
            assertEquals(original.messageOperationId, adopted.messageOperationId)
            assertEquals(staged.schema, adopted.adoption?.sourceSchema)
            assertFalse(assertNotNull(adopted.adoption).initialized)
            assertEquals(adopted, f.codec.decode(f.codec.encode(adopted)))
            assertEquals(f.target, f.codec.handoverIntent(adopted))
        }
    }

    @Test
    fun exactBootstrapReplayPreservesRotatedTokensAndInitializedState() = runTest {
        val f = SdkAdoptionFixture.create()
        val rotated =
            f.codec.withAdoptedTokens(
                f.adopted,
                f.command.id,
                "hris_mxg_a_" + "e".repeat(43),
                "hris_mxg_r_" + "f".repeat(43),
            )
        val initialized = f.codec.initializedAdoption(rotated, f.command.id)
        assertEquals(
            initialized,
            f.codec.stageAdoption(f.codec.decode(f.codec.encode(initialized)), f.command, f.gateway),
        )
        assertEquals(f.gateway.accessToken, initialized.adoption?.initialAccessToken)
        assertNotEquals(f.gateway.accessToken, initialized.adoption?.accessToken)
        assertEquals(f.original.accessToken, initialized.accessToken)
        assertFailsWith<IllegalArgumentException> {
            f.codec.stageAdoption(
                initialized,
                f.command,
                f.gateway.copy(accessToken = "hris_mxg_a_" + "g".repeat(43)),
            )
        }
    }

    @Test
    fun legacyEntryPointsAndWrongAdoptionOperationCannotUpdateAGroupEnvelope() = runTest {
        val f = SdkAdoptionFixture.create()
        assertFailsWith<IllegalArgumentException> {
            f.codec.withTokens(f.adopted, f.original.accessToken, f.original.refreshToken)
        }
        assertFailsWith<IllegalArgumentException> { f.codec.initialized(f.adopted) }
        assertFailsWith<IllegalArgumentException> {
            f.codec.replaceCredentials(f.adopted, f.originalCommand, f.originalGateway)
        }
        assertFailsWith<IllegalArgumentException> {
            f.codec.withAdoptedTokens(
                f.adopted,
                SETUP_OPERATION,
                f.gateway.accessToken,
                f.gateway.refreshToken,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            f.codec.initializedAdoption(f.adopted, SETUP_OPERATION)
        }
        assertFailsWith<IllegalArgumentException> {
            f.codec.stageAdoption(f.original, f.command, f.gateway)
        }
    }

    @Test
    fun explicitNewGatewayGenerationKeepsSourceAndRejectsOldCallbacks() = runTest {
        val f = SdkAdoptionFixture.create()
        val request = f.bootstrap.copy(operationId = SETUP_JOB, gatewayVersion = 0)
        val command = f.command.copy(request = request)
        val gateway =
            f.gateway.copy(
                version = 1,
                accessToken = "hris_mxg_a_" + "g".repeat(43),
                refreshToken = "hris_mxg_r_" + "h".repeat(43),
            )
        assertFailsWith<IllegalArgumentException> {
            f.codec.stageAdoption(f.adopted, command, gateway)
        }
        val next = f.codec.replaceAdoptedCredentials(f.adopted, command, gateway)
        assertEquals(
            f.adopted,
            next.copy(
                credentialRevision = f.adopted.credentialRevision,
                adoption = f.adopted.adoption,
            ),
        )
        assertEquals(SETUP_JOB, next.adoption?.bootstrapOperationId)
        assertFalse(assertNotNull(next.adoption).initialized)
        assertEquals(next, f.codec.stageAdoption(next, command, gateway))
        assertFailsWith<IllegalArgumentException> {
            f.codec.withAdoptedTokens(next, f.command.id, gateway.accessToken, gateway.refreshToken)
        }
        assertFailsWith<IllegalArgumentException> {
            f.codec.replaceAdoptedCredentials(next, f.command, f.gateway)
        }
    }

    @Test
    fun changedTargetOriginalIdentityOrNativeParentCannotStageCredentials() = runTest {
        val f = SdkAdoptionFixture.create()
        for (request in
            listOf(
                f.bootstrap.copy(binding = f.binding.copy(nativeSessionId = SETUP_JOB)),
                f.bootstrap.copy(binding = f.binding.copy(spaceId = SETUP_JOB)),
                f.bootstrap.copy(deviceId = SETUP_DEVICE),
                f.bootstrap.copy(deviceVersion = 2),
                f.bootstrap.copy(matrixDeviceId = "HRIS_" + "f".repeat(32)),
            )) {
            assertFailsWith<IllegalArgumentException> {
                f.codec.stageAdoption(f.staged, f.command.copy(request = request), f.gateway)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            f.codec.stageAdoption(
                f.staged,
                f.command,
                f.gateway.copy(matrixUserId = "@other:example.test"),
            )
        }
    }

    @Test
    fun malformedOrMixedSchemasAndRevisionOverflowNeverSerializeAsAdopted() = runTest {
        val f = SdkAdoptionFixture.create()
        val adoption = assertNotNull(f.adopted.adoption)
        for (record in
            listOf(
                f.adopted.copy(schema = 3),
                f.adopted.copy(adoption = null),
                f.adopted.copy(handover = null),
                f.adopted.copy(adoption = adoption.copy(sourceSchema = 1)),
                f.adopted.copy(adoption = adoption.copy(sourceSchema = 2)),
                f.adopted.copy(adoption = adoption.copy(spaceId = SETUP_JOB)),
                f.adopted.copy(adoption = adoption.copy(nativeSessionId = SETUP_JOB)),
                f.adopted.copy(adoption = adoption.copy(deviceVersion = 2)),
                f.adopted.copy(adoption = adoption.copy(accessToken = f.original.accessToken)),
                f.adopted.copy(adoption = adoption.copy(refreshToken = adoption.accessToken)),
                f.adopted.copy(adoption = adoption.copy(initialAccessToken = "bad")),
                f.adopted.copy(adoption = adoption.copy(expiresAt = "2026-01-01T00:00:00Z")),
            )) assertFailsWith<IllegalArgumentException> { f.codec.encode(record) }
        assertFailsWith<IllegalArgumentException> {
            f.codec.initializedAdoption(
                f.adopted.copy(credentialRevision = 9_007_199_254_740_991L),
                f.command.id,
            )
        }
    }
}
