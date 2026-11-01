package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationIdentity
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationIdentityTest {
    @Test
    fun localIdentitySurvivesAgeAndNativeFamilyReplacementWithoutSdkAccess() = runTest {
        val f = Fixture()
        f.stored = f.identity
        f.control.access.now += 30.days
        f.control.access.session =
            f.control.access.initial.copy(
                binding = f.control.access.initial.binding.copy(serverId = "new-family")
            )
        f.control.access.workspace =
            f.control.access.selected.copy(
                admission = f.control.access.selected.admission.copy(familyId = "new-family")
            )
        val result =
            assertIs<AppResult.Success<CollaborationRead<CollaborationSelfIdentity?>>>(
                    f.reader.current("selection")
                )
                .value
        assertEquals("new-family", result.owner.nativeSessionId)
        assertEquals(f.identity, result.value)
        assertEquals(0, f.nativeReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun explicitLocalAcquisitionRetainsOnlyTheOwnedSdkIdentity() = runTest {
        val f = Fixture()
        assertEquals(
            f.identity,
            assertIs<AppResult.Success<CollaborationRead<CollaborationSelfIdentity?>>>(
                    f.reader.acquire("selection", f.deviceId)
                )
                .value
                .value,
        )
        assertEquals(f.control.owner, f.nativeOwner)
        assertEquals(f.identity, f.stored)
        assertEquals(1, f.writes)
        assertTrue(f.control.calls.isEmpty())
        assertEquals(0, f.control.access.calls)
    }

    @Test
    fun missingOrFailedNativeRecordDoesNotDeleteCachedIdentity() = runTest {
        val f = Fixture()
        f.stored = f.identity
        f.native = { AppResult.Success(null) }
        assertNull(
            assertIs<AppResult.Success<CollaborationRead<CollaborationSelfIdentity?>>>(
                    f.reader.acquire("selection", f.deviceId)
                )
                .value
                .value
        )
        f.native = { f.control.storageFailure }
        assertEquals(f.control.storageFailure, f.reader.acquire("selection", f.deviceId))
        assertEquals(
            f.identity,
            assertIs<AppResult.Success<CollaborationRead<CollaborationSelfIdentity?>>>(
                    f.reader.current("selection")
                )
                .value
                .value,
        )
        assertEquals(0, f.writes)
    }

    @Test
    fun aChangedProviderIdentityRequiresExplicitMigration() = runTest {
        val f = Fixture()
        f.stored = CollaborationSelfIdentity("@earlier:example.test")
        assertEquals(
            "client.collaboration_identity_changed",
            assertIs<AppResult.Failed>(f.reader.acquire("selection", f.deviceId)).failure.code,
        )
        assertEquals("@earlier:example.test", f.stored!!.matrixUserId)
    }

    @Test
    fun knownDenialBlocksBothLocalReadAndAcquisitionWithoutRemovingFacts() = runTest {
        val f = Fixture()
        f.stored = f.identity
        f.control.access.admission.deny(f.denied)
        assertEquals(f.denied, assertIs<AppResult.Failed>(f.reader.current("selection")).failure)
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.reader.acquire("selection", f.deviceId)).failure,
        )
        assertEquals(0, f.localReads)
        assertEquals(0, f.nativeReads)
        assertEquals(f.identity, f.stored)
    }

    @Test
    fun newDenialOrReplacedOwnerAfterSdkReadPreventsPersistence() = runTest {
        for (deny in listOf(false, true)) {
            val f = Fixture()
            f.native = {
                if (deny) f.control.access.admission.deny(f.denied)
                else f.control.access.session = null
                AppResult.Success(f.identity)
            }
            assertIs<AppResult.Failed>(f.reader.acquire("selection", f.deviceId))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun lateCancelledSdkReadCannotPersistOrPublish() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.native = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                AppResult.Success(f.identity)
            }
        }
        val job = async { f.reader.acquire("selection", f.deviceId) }
        try {
            entered.await()
            job.cancel()
        } finally {
            release.complete(Unit)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, f.writes)
        assertNull(f.stored)
    }

    @Test
    fun replacedAccountDuringCacheReadOrWriteCannotPublish() = runTest {
        for (write in listOf(false, true)) {
            val f = Fixture()
            if (write) f.beforeWrite = { f.control.access.session = null }
            else {
                f.stored = f.identity
                f.beforeRead = { f.control.access.session = null }
            }
            val result =
                if (write) f.reader.acquire("selection", f.deviceId)
                else f.reader.current("selection")
            assertIs<AppResult.Failed>(result)
        }
    }

    @Test
    fun invalidDeviceOrUntrustedNativeSenderCannotEnterTheIdentityCache() = runTest {
        val f = Fixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.reader.acquire("selection", "bad-device")).failure.kind,
        )
        assertEquals(0, f.nativeReads)
        f.native = { AppResult.Success(CollaborationSelfIdentity("arbitrary-sender")) }
        assertEquals(
            "client.collaboration_identity_invalid",
            assertIs<AppResult.Failed>(f.reader.acquire("selection", f.deviceId)).failure.code,
        )
        assertEquals(0, f.writes)
    }

    private class Fixture {
        val control = CollaborationCommandFixture()
        val deviceId = "aaaaaaaa-0000-0000-0000-000000000001"
        val identity = CollaborationSelfIdentity("@fictional:example.test")
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Fixture denied",
                code = "collaboration_access_denied",
            )
        var stored: CollaborationSelfIdentity? = null
        var native: suspend () -> AppResult<CollaborationSelfIdentity?> = {
            AppResult.Success(identity)
        }
        var nativeOwner: CollaborationOwner? = null
        var nativeReads = 0
        var localReads = 0
        var writes = 0
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        val identities =
            object : CollaborationIdentityRepository {
                override suspend fun current(
                    access: CollaborationLocalAccess
                ): AppResult<CollaborationSelfIdentity?> {
                    localReads++
                    beforeRead()
                    return AppResult.Success(stored)
                }

                override suspend fun retain(
                    access: CollaborationLocalAccess,
                    identity: CollaborationSelfIdentity,
                ): AppResult<Boolean> {
                    writes++
                    beforeWrite()
                    if (stored != null && stored != identity) return AppResult.Success(false)
                    stored = identity
                    return AppResult.Success(true)
                }
            }
        val runtimes =
            object : CollaborationRuntimeRepository by CollaborationDeliveryFixture().runtimes {
                override suspend fun selfIdentity(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationSelfIdentity?> {
                    assertEquals(this@Fixture.deviceId, deviceId)
                    nativeOwner = owner
                    nativeReads++
                    return native()
                }
            }
        val reader =
            ReadCollaborationIdentity(
                identities,
                runtimes,
                control.access.admission.repository,
                control.access.sessions,
                control.access.workspaces,
                control.server,
                control.build,
                control.clock,
            )
    }
}
