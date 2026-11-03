package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationIdentity
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationBackgroundIdentityTest {
    @Test
    fun admittedBackgroundIdentityUsesItsExactOwnerWithoutSelectionOrOpeningRuntime() = runTest {
        val f = Fixture()
        f.elapsed += 40.seconds
        f.base.setup.now += 40.seconds
        assertEquals(
            AppResult.Success(
                CollaborationRead<CollaborationSelfIdentity?>(f.delivery.owner, f.identity)
            ),
            f.acquire(),
        )
        assertEquals(f.identity, f.saved)
        assertEquals(1, f.reads)
        assertEquals(1, f.writes)
        assertEquals(0, f.delivery.acquisitions)
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun foreignDeviceAndExpiredGrantCannotReadSdkMetadata() = runTest {
        val f = Fixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.reader.acquire(f.scope, deliveryId(99))).failure.kind,
        )
        f.elapsed += 60.seconds
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun monotonicExpiryAndReplacementFamilyAfterSdkReadCannotPersist() = runTest {
        for (expire in listOf(false, true)) {
            val f = Fixture()
            f.beforeReturn = {
                if (expire) f.elapsed += 60.seconds
                else
                    f.base.setup.session =
                        f.base.setup.session!!.let {
                            it.copy(binding = it.binding.copy(serverId = deliveryId(99)))
                        }
            }
            assertIs<AppResult.Failed>(f.acquire())
            assertEquals(1, f.reads)
            assertEquals(0, f.writes)
            assertNull(f.base.gate.value.denial)
        }
    }

    @Test
    fun companyDenialWinsConcurrentGrantExpiryWithoutRemovingOwnedIdentity() = runTest {
        val f = Fixture()
        f.saved = f.identity
        f.beforeReturn = {
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            f.elapsed += 60.seconds
        }
        assertEquals(AppResult.Failed(f.base.denied), f.acquire())
        assertEquals(0, f.writes)
        assertEquals(f.identity, f.saved)
    }

    @Test
    fun failedOrAbsentMetadataPreservesThePreviouslyAcquiredIdentity() = runTest {
        val f = Fixture()
        f.saved = f.identity
        f.result = AppResult.Success(null)
        assertNull(
            assertIs<AppResult.Success<CollaborationRead<CollaborationSelfIdentity?>>>(f.acquire())
                .value
                .value
        )
        val failure = AppResult.Failed(Failure(FailureKind.Network, "Fictional unavailable SDK"))
        f.result = failure
        assertEquals(failure, f.acquire())
        assertEquals(f.identity, f.saved)
        assertEquals(0, f.writes)
    }

    @Test
    fun lateCancelledSdkResultCannotPersistAndTheCallerJoinsIt() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeReturn = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val request = async { f.acquire() }
        try {
            entered.await()
            request.cancel()
            assertFalse(request.isCompleted)
        } finally {
            release.complete(Unit)
        }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(0, f.writes)
    }

    @Test
    fun deniedSdkResponseClosesTheGateEvenWhenTheRequestWasCancelled() = runTest {
        val f = Fixture()
        f.result = AppResult.Failed(f.base.denied)
        f.beforeReturn = { currentCoroutineContext().cancel() }
        val request = launch { f.acquire() }
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(f.base.denied, f.base.gate.value.denial)
        assertEquals(0, f.writes)
    }

    @Test
    fun replacementAfterCommittedWriteDoesNotPublishOrEraseTheOldAccountsFact() = runTest {
        val f = Fixture()
        f.afterWrite = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.acquire())
        assertEquals(1, f.writes)
        assertEquals(f.identity, f.saved)
    }

    private class Fixture {
        val delivery = CollaborationDeliveryFixture()
        val base = delivery.base
        val elapsed = TestTimeSource()
        val scope = delivery.historyBackground(elapsed)
        val identity = CollaborationSelfIdentity("@fictional:example.test")
        var saved: CollaborationSelfIdentity? = null
        var reads = 0
        var writes = 0
        var beforeReturn: suspend () -> Unit = {}
        var afterWrite: suspend () -> Unit = {}
        var result: AppResult<CollaborationSelfIdentity?> = AppResult.Success(identity)
        val identities =
            object : CollaborationIdentityRepository {
                override suspend fun current(access: CollaborationLocalAccess) =
                    AppResult.Success(saved)

                override suspend fun retain(
                    access: CollaborationLocalAccess,
                    identity: CollaborationSelfIdentity,
                ): AppResult<Boolean> {
                    assertEquals(scope.admission.owner.localListAccess(), access)
                    writes++
                    saved = identity
                    afterWrite()
                    return AppResult.Success(true)
                }
            }
        val runtime =
            object : CollaborationRuntimeRepository by delivery.runtimes {
                override suspend fun selfIdentity(
                    owner: CollaborationOwner,
                    deviceId: String,
                ): AppResult<CollaborationSelfIdentity?> {
                    assertEquals(scope.admission.owner, owner)
                    assertEquals(delivery.device.deviceId, deviceId)
                    reads++
                    beforeReturn()
                    return result
                }
            }
        val reader =
            ReadCollaborationIdentity(
                identities,
                runtime,
                object : CollaborationListRepository by base.lists {
                    override suspend fun restrict(
                        access: CollaborationLocalAccess,
                        failure: Failure,
                    ): AppResult<Boolean> {
                        assertEquals(scope.admission.owner.localListAccess(), access)
                        base.gate.value = CollaborationListAdmission("denied", failure)
                        return AppResult.Success(true)
                    }
                },
                base.setup.sessions,
                base.setup.workspaces,
                base.server,
                base.build,
                base.clock,
            )

        suspend fun acquire() = reader.acquire(scope, delivery.device.deviceId)
    }
}
