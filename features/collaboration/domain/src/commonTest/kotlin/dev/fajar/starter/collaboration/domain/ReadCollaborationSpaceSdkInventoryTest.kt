package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReadCollaborationSpaceSdkInventoryTest {
    @Test
    fun freshLoginReadsTheOriginalPhysicalNamespaceEvenAfterExpiryAndCompanyRemoval() = runTest {
        val f = Fixture()
        val original = f.registration.sdkOwner
        val current = f.owner.copy(parent = f.owner.parent.copy(sessionId = "fresh-login"))
        f.registration.session(
            f.setup.native.session.copy(
                id = current.parent.sessionId,
                binding = f.setup.native.session.binding.copy(serverId = f.owner.spaceId),
            )
        )
        f.setup.native.setup.now += 1_000_000.seconds
        f.setup.native.setup.workspace = null
        assertEquals(AppResult.Success(f.facts), f.read()(current, f.setup.registrationId))
        assertEquals(
            listOf(original.copy(sessionId = "fresh-login") to f.registration.sdkDevice),
            f.reads,
        )
        assertEquals(0, f.registration.identityReads)
        assertEquals(0, f.registration.statusReads)
        assertEquals(0, f.registration.sdkReads)
        assertTrue(f.registration.sent.isEmpty())
        assertTrue(f.setup.base.requests.isEmpty())
    }

    @Test
    fun missingFilesOrCredentialsRemainOwnedFactsWithoutCreatingOrReplayingAnything() = runTest {
        val f = Fixture()
        val missing =
            listOf(
                f.facts.copy(
                    directoryPresent = false,
                    stateDatabasePresent = false,
                    cryptoDatabasePresent = false,
                ),
                f.facts.copy(credential = null),
                f.facts.copy(stateDatabasePresent = false),
                f.facts.copy(cryptoDatabasePresent = false),
            )
        for (value in missing) {
            f.response = { AppResult.Success(value) }
            assertEquals(AppResult.Success(value), f.read()(f.owner, f.setup.registrationId))
        }
        assertEquals(0, f.registration.inserts)
        assertTrue(f.registration.sent.isEmpty())
        assertTrue(f.setup.sent.isEmpty())
    }

    @Test
    fun pendingAndUnconfirmedRegistrationProvenanceIsReadableButNotAReceipt() = runTest {
        val f = Fixture()
        for (record in
            listOf(
                f.setup.registered.copy(
                    revision = 0,
                    attempts = 0,
                    outcome = CollaborationSpaceRegistrationOutcome.Pending,
                ),
                f.setup.registered.copy(
                    revision = 1,
                    outcome = CollaborationSpaceRegistrationOutcome.Unconfirmed(null),
                ),
            )) {
            f.registration.records.value = AppResult.Success(record)
            assertEquals(AppResult.Success(f.facts), f.read()(f.owner, f.setup.registrationId))
            assertEquals(AppResult.Success(record), f.registration.records.value)
        }
    }

    @Test
    fun accountReplacementBeforeOrDuringEachLocalReadCannotPublishPrivateFacts() = runTest {
        for (stage in 0..2) {
            val f = Fixture()
            val change = {
                f.registration.session(
                    f.setup.native.session.copy(
                        user = f.setup.native.session.user.copy(id = f.owner.spaceId)
                    )
                )
            }
            when (stage) {
                0 -> change()
                1 -> f.registration.beforeCurrent = { change() }
                2 -> f.response = {
                        change()
                        AppResult.Success(f.facts)
                    }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read()(f.owner, f.setup.registrationId)).failure.kind,
            )
            assertEquals(if (stage == 2) 1 else 0, f.reads.size)
        }
    }

    @Test
    fun foreignRegistrationAndWrongRequestedOperationCannotReachPrivateStorage() = runTest {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.read()(f.owner, f.owner.spaceId))
        f.registration.records.value =
            AppResult.Success(
                f.setup.registered.copy(
                    command =
                        f.setup.registered.command.copy(
                            binding =
                                f.setup.registered.command.binding.copy(accountId = f.owner.spaceId)
                        )
                )
            )
        assertIs<AppResult.Failed>(f.read()(f.owner, f.setup.registrationId))
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun foreignIdentityNamespaceAndImpossibleFileFactsAreRejected() = runTest {
        val f = Fixture()
        val credential = assertNotNull(f.facts.credential)
        val frozen =
            CollaborationRuntimeHandoverIntent(
                f.owner.spaceId,
                f.owner.spaceId,
                f.registration.sdkDevice,
                0,
                "b".repeat(64),
            )
        for (value in
            listOf(
                f.facts.copy(binding = f.facts.binding.copy(nativeSessionId = f.owner.spaceId)),
                f.facts.copy(deviceId = f.owner.spaceId),
                f.facts.copy(directoryPresent = false),
                f.facts.copy(credential = credential.copy(matrixUserId = "@foreign:fixture.test")),
                f.facts.copy(credential = credential.copy(matrixDeviceId = "OTHER")),
                f.facts.copy(credential = credential.copy(messageOperationId = "bad")),
                f.facts.copy(credential = credential.copy(revision = -1)),
                f.facts.copy(credential = credential.copy(handover = frozen)),
            )) {
            f.response = { AppResult.Success(value) }
            assertIs<AppResult.Failed>(f.read()(f.owner, f.setup.registrationId))
        }
        val valid =
            f.facts.copy(
                credential =
                    credential.copy(
                        handover = frozen.copy(fingerprint = f.registration.sdkIdentity.fingerprint)
                    )
            )
        f.response = { AppResult.Success(valid) }
        assertEquals(AppResult.Success(valid), f.read()(f.owner, f.setup.registrationId))
    }

    @Test
    fun storageFailureIsPreservedAndCancellationOrTimeoutCannotReturnLateFacts() = runTest {
        val f = Fixture()
        val failed = AppResult.Failed(f.setup.base.storageFailure)
        f.response = { failed }
        assertEquals(failed, f.read()(f.owner, f.setup.registrationId))
        val entered = CompletableDeferred<Unit>()
        var closed = 0
        f.response = {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                closed++
            }
        }
        val pending = async { f.read()(f.owner, f.setup.registrationId) }
        entered.await()
        pending.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, closed)
        assertEquals(
            "client.collaboration_inventory_timeout",
            assertIs<AppResult.Failed>(f.read()(f.owner, f.setup.registrationId)).failure.code,
        )
        assertEquals(2, closed)
    }

    private class Fixture {
        val setup = SpaceSetupFixture()
        val registration = setup.registration
        val owner = setup.owner
        val reads = mutableListOf<Pair<CollaborationOwner, String>>()
        val facts =
            CollaborationSdkInventory(
                registration.sdkOwner.setupBinding(),
                registration.sdkDevice,
                true,
                true,
                true,
                CollaborationSdkCredentialInventory(
                    registration.sdkIdentity.matrixUserId,
                    registration.sdkIdentity.matrixDeviceId,
                    setup.registrationId,
                    setup.registrationId,
                    2,
                    true,
                    null,
                ),
            )
        var response: suspend () -> AppResult<CollaborationSdkInventory> = {
            AppResult.Success(facts)
        }
        private val inventory =
            object : CollaborationSdkInventoryRepository {
                override suspend fun inspect(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationSdkInventory> {
                    reads += owner to deviceId
                    return response()
                }
            }

        fun read() =
            ReadCollaborationSpaceSdkInventory(
                registration.commands,
                inventory,
                setup.base.sessions,
                setup.native.server,
            )
    }
}
