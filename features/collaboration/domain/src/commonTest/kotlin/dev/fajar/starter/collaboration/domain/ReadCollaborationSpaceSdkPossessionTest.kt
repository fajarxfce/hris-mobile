package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSdkPossessionRepository
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceSdkPossession
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ReadCollaborationSpaceSdkPossessionTest {
    @Test
    fun uninitializedHandoverKeepsOriginalPrivateProofWithoutGrantingAnotherIdentity() = runTest {
        val f = Fixture()
        val target =
            CollaborationRuntimeHandoverIntent(
                f.setup.owner.spaceId,
                f.setup.owner.spaceId,
                f.setup.registration.sdkDevice,
                0,
                f.proof.identity.fingerprint,
            )
        val retained =
            f.proof.copy(
                inventory =
                    f.proof.inventory.copy(
                        credential =
                            f.proof.inventory.credential!!.copy(
                                initialized = false,
                                handover = target,
                            )
                    )
            )
        f.response = { AppResult.Success(retained) }
        assertEquals(AppResult.Success(retained), f.read()(f.setup.owner, f.setup.registrationId))
        f.response = {
            AppResult.Success(
                retained.copy(identity = retained.identity.copy(fingerprint = "b".repeat(64)))
            )
        }
        assertIs<AppResult.Failed>(f.read()(f.setup.owner, f.setup.registrationId))
        assertTrue(f.setup.registration.sent.isEmpty())
        assertTrue(f.setup.sent.isEmpty())
        assertTrue(f.setup.base.requests.isEmpty())
    }

    @Test
    fun newLocalLoginCanProveOriginalKeysAfterParentExpiryAndCompanyRemovalWithoutHttp() = runTest {
        val f = Fixture()
        val owner =
            f.setup.owner.copy(parent = f.setup.owner.parent.copy(sessionId = "fresh-login"))
        f.setup.registration.session(f.setup.native.session.copy(id = owner.parent.sessionId))
        f.setup.native.setup.now += 1_000_000.seconds
        f.setup.native.setup.workspace = null
        assertEquals(AppResult.Success(f.proof), f.read()(owner, f.setup.registrationId))
        assertEquals(
            listOf(
                f.setup.registration.sdkOwner.copy(sessionId = "fresh-login") to
                    f.setup.registration.sdkDevice
            ),
            f.reads,
        )
        assertEquals(0, f.setup.registration.sdkReads)
        assertEquals(0, f.setup.registration.statusReads)
        assertTrue(f.setup.registration.sent.isEmpty())
        assertTrue(f.setup.base.requests.isEmpty())
    }

    @Test
    fun missingPrivateMaterialIsConflictAndNeverCreatesAReplacement() = runTest {
        val f = Fixture()
        f.response = { AppResult.Success(null) }
        val failure =
            assertIs<AppResult.Failed>(f.read()(f.setup.owner, f.setup.registrationId)).failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertEquals("client.collaboration_original_device_unavailable", failure.code)
        assertEquals(0, f.setup.registration.inserts)
        assertTrue(f.setup.registration.sent.isEmpty())
        assertTrue(f.setup.sent.isEmpty())
    }

    @Test
    fun changedAccountDuringEitherReadNeverReceivesTheOriginalIdentity() = runTest {
        for (stage in 0..2) {
            val f = Fixture()
            val replace = {
                f.setup.registration.session(
                    f.setup.native.session.copy(
                        user = f.setup.native.session.user.copy(id = f.setup.owner.spaceId)
                    )
                )
            }
            when (stage) {
                0 -> replace()
                1 -> f.setup.registration.beforeCurrent = { replace() }
                2 -> f.response = {
                        replace()
                        AppResult.Success(f.proof)
                    }
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.read()(f.setup.owner, f.setup.registrationId))
                    .failure
                    .kind,
            )
            assertEquals(if (stage == 2) 1 else 0, f.reads.size)
        }
    }

    @Test
    fun wrongRegistrationCannotReadKeysAndAChangedKeyOrNamespaceCannotProveTheOriginal() = runTest {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.read()(f.setup.owner, f.setup.owner.spaceId))
        assertTrue(f.reads.isEmpty())
        val inventory = f.proof.inventory
        for (value in
            listOf(
                f.proof.copy(identity = f.proof.identity.copy(fingerprint = "b".repeat(64))),
                f.proof.copy(identity = f.proof.identity.copy(matrixDeviceId = "OTHER")),
                f.proof.copy(
                    inventory =
                        inventory.copy(
                            binding =
                                inventory.binding.copy(nativeSessionId = f.setup.owner.spaceId)
                        )
                ),
                f.proof.copy(inventory = inventory.copy(deviceId = f.setup.owner.spaceId)),
                f.proof.copy(inventory = inventory.copy(directoryPresent = false)),
                f.proof.copy(inventory = inventory.copy(cryptoDatabasePresent = false)),
                f.proof.copy(inventory = inventory.copy(credential = null)),
                f.proof.copy(
                    inventory =
                        inventory.copy(
                            credential =
                                inventory.credential!!.copy(matrixUserId = "@foreign:fixture.test")
                        )
                ),
            )) {
            f.response = { AppResult.Success(value) }
            assertIs<AppResult.Failed>(f.read()(f.setup.owner, f.setup.registrationId))
        }
    }

    @Test
    fun rotatedCredentialsAndUnconfirmedRegistrationDoNotEraseLocalPrivateKeys() = runTest {
        val f = Fixture()
        val retained =
            f.setup.registered.copy(
                revision = 1,
                outcome = CollaborationSpaceRegistrationOutcome.Unconfirmed(null),
            )
        f.setup.registration.records.value = AppResult.Success(retained)
        val rotated =
            f.proof.copy(
                inventory =
                    f.proof.inventory.copy(
                        stateDatabasePresent = false,
                        credential = f.proof.inventory.credential!!.copy(initialized = false),
                    )
            )
        f.response = { AppResult.Success(rotated) }
        assertEquals(AppResult.Success(rotated), f.read()(f.setup.owner, f.setup.registrationId))
        assertEquals(AppResult.Success(retained), f.setup.registration.records.value)
        assertTrue(f.setup.sent.isEmpty())
    }

    @Test
    fun failureCancellationAndTimeoutNeverReturnLateProof() = runTest {
        val f = Fixture()
        val failed = AppResult.Failed(f.setup.base.storageFailure)
        f.response = { failed }
        assertEquals(failed, f.read()(f.setup.owner, f.setup.registrationId))
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
        val pending = async { f.read()(f.setup.owner, f.setup.registrationId) }
        entered.await()
        pending.cancel()
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, closed)
        assertEquals(
            "client.collaboration_private_identity_timeout",
            assertIs<AppResult.Failed>(f.read()(f.setup.owner, f.setup.registrationId)).failure.code,
        )
        assertEquals(2, closed)
    }

    private class Fixture {
        val setup = SpaceSetupFixture()
        val proof =
            CollaborationSdkPossession(
                CollaborationSdkInventory(
                    setup.registration.sdkOwner.setupBinding(),
                    setup.registration.sdkDevice,
                    true,
                    true,
                    true,
                    CollaborationSdkCredentialInventory(
                        setup.registration.sdkIdentity.matrixUserId,
                        setup.registration.sdkIdentity.matrixDeviceId,
                        setup.registrationId,
                        setup.registrationId,
                        2,
                        true,
                        null,
                    ),
                ),
                setup.registration.sdkIdentity,
            )
        val reads = mutableListOf<Pair<CollaborationOwner, String>>()
        var response: suspend () -> AppResult<CollaborationSdkPossession?> = {
            AppResult.Success(proof)
        }
        private val repository =
            object : CollaborationSdkPossessionRepository {
                override suspend fun readPrivateIdentity(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationSdkPossession?> {
                    reads += owner to deviceId
                    return response()
                }
            }

        fun read() =
            ReadCollaborationSpaceSdkPossession(
                setup.registration.commands,
                repository,
                setup.base.sessions,
                setup.native.server,
            )
    }
}
