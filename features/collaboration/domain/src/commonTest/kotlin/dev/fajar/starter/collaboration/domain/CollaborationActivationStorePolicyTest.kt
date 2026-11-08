package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationActivationStorePolicyTest {
    @Test
    fun emptyFirstStoreIsDistinctFromMissingEstablishedKeys() {
        val f = CollaborationActivationFixture()
        val current = requireNotNull(f.device)
        assertEquals(
            CollaborationActivationStore.FIRST_INITIALIZATION,
            collaborationActivationStore(f.command.owner, current, null, f.inventory),
        )
        for (version in listOf(0L, 1L)) {
            val established =
                current.copy(gateway = CollaborationGatewayStatus("head", version, 5, 1, 2, null))
            assertEquals(
                CollaborationActivationStore.RECOVERY_REQUIRED,
                collaborationActivationStore(f.command.owner, established, null, f.inventory),
            )
        }
        assertEquals(
            CollaborationActivationStore.RECOVERY_REQUIRED,
            collaborationActivationStore(
                f.command.owner,
                current,
                null,
                f.inventory.copy(directoryPresent = true),
            ),
        )
    }

    @Test
    fun uncertainFirstBootstrapCanRecoverItsOwnReplyButNotAnAdvancedGateway() {
        val f = CollaborationActivationFixture()
        val original = f.command.retain(CollaborationSetupAction.Bootstrap("device", 1, null))
        val current = requireNotNull(f.device)
        assertEquals(
            CollaborationActivationStore.FIRST_INITIALIZATION,
            collaborationActivationStore(
                f.command.owner,
                current.copy(gateway = CollaborationGatewayStatus("head", 0, 5, 1, 2, null)),
                original,
                f.inventory,
            ),
        )
        assertEquals(
            CollaborationActivationStore.RECOVERY_REQUIRED,
            collaborationActivationStore(
                f.command.owner,
                current.copy(gateway = CollaborationGatewayStatus("head", 1, 5, 1, 2, null)),
                original,
                f.inventory,
            ),
        )
    }

    @Test
    fun confirmedInitialInstallationCanResumeOnlyItsOwnUninitializedRecord() = runTest {
        val f = CollaborationActivationFixture()
        val original = f.command.retain(CollaborationSetupAction.Bootstrap("device", 1, null))
        val gateway =
            assertIs<AppResult.Success<CollaborationGateway>>(f.command.gatewayResponse()).value
        val confirmed =
            original.copy(attempts = 1, outcome = CollaborationSetupOutcome.Bootstrapped(gateway))
        val credential =
            CollaborationSdkCredentialInventory(
                "matrix-user",
                "matrix-device",
                original.command.id,
                original.command.id,
                0,
                false,
                null,
            )
        val staged = f.inventory.copy(directoryPresent = true, credential = credential)
        assertEquals(
            CollaborationActivationStore.FIRST_INITIALIZATION,
            collaborationActivationStore(
                f.command.owner,
                requireNotNull(f.device),
                confirmed,
                staged,
            ),
        )
        for (changed in
            listOf(
                credential.copy(initialized = true),
                credential.copy(messageOperationId = "older-original"),
                credential.copy(bootstrapOperationId = "other-operation"),
                credential.copy(matrixDeviceId = "other-device"),
            )) assertEquals(
            CollaborationActivationStore.ORIGINAL_ACCOUNT,
            collaborationActivationStore(
                f.command.owner,
                requireNotNull(f.device),
                confirmed,
                staged.copy(credential = changed),
            ),
        )
    }

    @Test
    fun foreignScopeAndInconsistentPresenceCannotAuthorizeInitialization() {
        val f = CollaborationActivationFixture()
        val current = requireNotNull(f.device)
        for (changed in
            listOf(
                f.inventory.copy(deviceId = "other-device"),
                f.inventory.copy(binding = f.inventory.binding.copy(accountId = "other-account")),
                f.inventory.copy(cryptoDatabasePresent = true),
                f.inventory.copy(stateDatabasePresent = true),
            )) assertEquals(
            CollaborationActivationStore.RECOVERY_REQUIRED,
            collaborationActivationStore(f.command.owner, current, null, changed),
        )
    }

    @Test
    fun possessionReadbackMustStillMatchTheInspectedAccount() {
        val f = CollaborationActivationFixture()
        f.retainPrivateAccount()
        val possessed = requireNotNull(f.privateIdentity)
        assertTrue(collaborationActivationPossessionMatches(f.inventory, possessed))
        assertFalse(collaborationActivationPossessionMatches(f.inventory, null))
        for (changed in
            listOf(
                possessed.copy(inventory = possessed.inventory.copy(cryptoDatabasePresent = false)),
                possessed.copy(identity = possessed.identity.copy(matrixUserId = "foreign")),
                possessed.copy(identity = possessed.identity.copy(matrixDeviceId = "foreign")),
                possessed.copy(identity = possessed.identity.copy(fingerprint = "invalid")),
            )) assertFalse(collaborationActivationPossessionMatches(f.inventory, changed))
    }
}
