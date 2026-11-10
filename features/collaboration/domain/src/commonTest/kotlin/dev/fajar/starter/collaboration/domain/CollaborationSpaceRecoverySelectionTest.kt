package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

class CollaborationSpaceRecoverySelectionTest {
    @Test
    fun absentRootPerformsNoRegistrationSdkOrProviderAcquisition() = runTest {
        val f = DeviceFixture()
        f.root = null
        assertEquals(AppResult.Success(null), f.load(f.owner.parent))
        assertTrue(f.adoption.transfer.events.isEmpty())
        assertEquals(0, f.adoption.installs)
    }

    @Test
    fun exactOriginalCoordinatesAreFoundWithoutAdoptingOrSyncing() = runTest {
        val f = DeviceFixture()
        assertEquals(
            AppResult.Success(
                CollaborationSpaceRecoveryDevice(
                    f.owner,
                    f.adoption.setup.registrationId,
                    f.adoption.operation,
                )
            ),
            f.load(f.owner.parent),
        )
        assertEquals(listOf("registration", "intent"), f.adoption.transfer.events)
        assertEquals(0, f.adoption.installs)
    }

    @Test
    fun missingRegistrationBootstrapOrHandoverCannotCreateReplacementWork() = runTest {
        for (mode in 0..2) {
            val f = DeviceFixture()
            when (mode) {
                0 -> f.adoption.setup.registration.records.value = AppResult.Success(null)
                1 -> f.adoption.setup.records.value = AppResult.Success(null)
                else -> f.adoption.transfer.retained = null
            }
            assertIs<AppResult.Failed>(f.load(f.owner.parent))
            assertEquals(0, f.adoption.installs)
            assertTrue(f.adoption.transfer.requests.isEmpty())
            assertEquals(0, f.adoption.setup.inserts)
        }
    }

    @Test
    fun changedLocalOwnerAfterDiscoveryRejectsTheOldRoot() = runTest {
        val f = DeviceFixture()
        f.afterRoot = { f.adoption.setup.native.setup.session = null }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.load(f.owner.parent)).failure.kind,
        )
        assertTrue(f.adoption.transfer.events.isEmpty())
    }

    @Test
    fun cancelledRootReadCannotProceedToPrivateOriginals() = runTest {
        val f = DeviceFixture()
        f.afterRoot = { currentCoroutineContext().cancel() }
        val job = launch {
            f.load(f.owner.parent)
            error("Cancelled publication")
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(f.adoption.transfer.events.isEmpty())
    }

    @Test
    fun stalledLocalDiscoveryHasABoundedCancelledLifetime() = runTest {
        val f = DeviceFixture()
        var released = false
        f.afterRoot = {
            try {
                awaitCancellation()
            } finally {
                released = true
            }
        }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.load(f.owner.parent)).failure.kind,
        )
        assertTrue(released)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun expiredCredentialsDoNotEraseLocalOriginalCoordinates() = runTest {
        val f = DeviceFixture()
        val session = f.adoption.setup.native.setup.session!!
        f.adoption.setup.native.setup.session =
            session.copy(
                tokens = session.tokens.copy(expiresAtEpochMillis = 0),
                binding = session.binding.copy(expiresAtEpochMillis = 0),
            )
        assertIs<AppResult.Success<CollaborationSpaceRecoveryDevice?>>(f.load(f.owner.parent))
        assertEquals(0, f.adoption.installs)
    }

    @Test
    fun oneRawItemIsReadAtTheRetainedBoundaryWithoutAdvancingIt() = runTest {
        val f = RoomFixture()
        val ready =
            f.snapshot.copy(
                progress =
                    f.snapshot.progress!!.copy(sweep = CollaborationSpaceRecoverySweep(id(701)))
            )
        assertEquals(AppResult.Success(f.page), f.loadPage(ready))
        assertEquals(listOf<Pair<CollaborationSpaceRoomBindingCursor?, Int>>(null to 1), f.reads)
        assertNull(ready.progress!!.sweep!!.pending)
    }

    @Test
    fun pendingOrCompletedProgressCannotReadAndClaimAnotherPage() = runTest {
        val f = RoomFixture()
        assertIs<AppResult.Failed>(f.loadPage(f.snapshot))
        val completed =
            f.snapshot.copy(
                progress =
                    f.snapshot.progress!!.copy(
                        sweep =
                            f.snapshot.progress!!.sweep!!.copy(pending = null, reachedEnd = true)
                    )
            )
        assertIs<AppResult.Failed>(f.loadPage(completed))
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun exactClaimResolvesItsOriginalWithoutProviderMetadata() = runTest {
        val f = RoomFixture()
        assertEquals(AppResult.Success(f.original.model.conversation), f.loadRoom(f.snapshot))
        assertEquals(listOf("binding"), f.original.calls)
        assertEquals(1, f.reads.size)
    }

    @Test
    fun exactClaimResolvesANewSpaceRoomWithoutCompanyOriginals() = runTest {
        val f = RoomFixture()
        val binding = f.original.model.binding
        f.original.retained =
            binding.copy(
                room =
                    binding.room.copy(origin = CollaborationSpaceRoomOrigin.SPACE, original = null),
                original = null,
            )
        assertEquals(AppResult.Success(f.original.model.conversation), f.loadRoom(f.snapshot))
        assertEquals(listOf("binding"), f.original.calls)
        assertEquals(1, f.reads.size)
    }

    @Test
    fun newSpaceRoomStillRejectsAReplacedInventoryNamespace() = runTest {
        val f = RoomFixture()
        val binding = f.original.model.binding
        f.original.retained =
            binding.copy(
                room =
                    binding.room.copy(origin = CollaborationSpaceRoomOrigin.SPACE, original = null),
                original = null,
            )
        f.page = f.page.copy(boundary = f.boundary.copy(generation = id(900)))
        assertIs<AppResult.Failed>(f.loadRoom(f.snapshot))
        assertEquals(listOf("binding"), f.original.calls)
    }

    @Test
    fun firstPageClaimAllowsANewerSameGenerationInventory() = runTest {
        val f = RoomFixture()
        f.page = f.page.copy(boundary = f.boundary.copy(revision = 8))
        assertEquals(AppResult.Success(f.original.model.conversation), f.loadRoom(f.snapshot))
    }

    @Test
    fun replacedNamespaceOrMissingBindingCannotTurnAnOldClaimIntoNewWork() = runTest {
        for (mode in 0..2) {
            val f = RoomFixture()
            when (mode) {
                0 -> f.original.retained = null
                1 -> f.page = f.page.copy(boundary = f.boundary.copy(generation = id(900)))
                else -> f.page = f.page.copy(boundary = f.boundary.copy(revision = 0))
            }
            assertIs<AppResult.Failed>(f.loadRoom(f.snapshot))
            assertFalse("transport" in f.original.calls)
        }
    }

    @Test
    fun emptyClaimDoesNotAcquireABindingOrAnySdkResource() = runTest {
        val f = RoomFixture()
        val empty =
            f.snapshot.copy(
                progress =
                    f.snapshot.progress!!.copy(
                        sweep =
                            f.snapshot.progress!!
                                .sweep!!
                                .copy(pending = CollaborationSpaceRecoveryClaim(null, null))
                    )
            )
        assertEquals(AppResult.Success(null), f.loadRoom(empty))
        assertTrue(f.original.calls.isEmpty())
        assertTrue(f.reads.isEmpty())
    }

    @Test
    fun changedOwnerAfterExactBindingReadCannotPublishItsConversation() = runTest {
        val f = RoomFixture()
        f.original.hooks["binding"] = { f.original.native.setup.session = null }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.loadRoom(f.snapshot)).failure.kind,
        )
        assertTrue(f.reads.isEmpty())
    }

    private class DeviceFixture {
        val adoption = SpaceRuntimeAdoptionFixture()
        val owner = adoption.setup.owner
        var root: CollaborationSpaceRoot? = CollaborationSpaceRoot(owner.spaceId)
        var afterRoot: suspend () -> Unit = {}
        val roots =
            object : CollaborationSpaceRootRepository {
                override fun observe(
                    access: CollaborationSpaceAccountAccess
                ): Flow<AppResult<CollaborationSpaceRoot?>> = error("No collector")

                override suspend fun current(
                    access: CollaborationSpaceAccountAccess
                ): AppResult<CollaborationSpaceRoot?> {
                    assertEquals(owner.parent.localSpaceAccountAccess(), access)
                    val value = root
                    afterRoot()
                    return AppResult.Success(value)
                }

                override suspend fun retain(
                    access: CollaborationSpaceAccountAccess,
                    root: CollaborationSpaceRoot,
                ): AppResult<Boolean> = error("No new root")
            }
        val load =
            LoadCollaborationSpaceRecoveryDevice(
                roots,
                adoption.setup.registration.commands,
                adoption.setup.commands,
                adoption.transfer.runtime,
                adoption.setup.base.sessions,
                adoption.setup.native.server,
                adoption.setup.build,
            )
    }

    private class RoomFixture {
        val original = SpaceOriginalDeliveryFixture()
        val owner = original.queue.owner
        val boundary =
            CollaborationSpaceRoomBindingBoundary(
                owner.parent.audience,
                owner.parent.accountId,
                owner.spaceId,
                id(700),
                7,
            )
        val snapshot =
            CollaborationSpaceRecoverySnapshot(
                owner,
                CollaborationSpaceRecoveryProgress(
                    id(702),
                    CollaborationSpaceRecoverySweep(
                        id(701),
                        boundary,
                        pending =
                            CollaborationSpaceRecoveryClaim(original.model.conversation.id, null),
                    ),
                ),
            )
        var page =
            CollaborationSpaceRoomBindingPage(boundary, listOf(original.model.binding), null, false)
        val reads = mutableListOf<Pair<CollaborationSpaceRoomBindingCursor?, Int>>()
        val inventory =
            object : CollaborationSpaceRoomBindingInventoryRepository {
                override suspend fun page(
                    access: CollaborationSpaceLocalAccess,
                    after: CollaborationSpaceRoomBindingCursor?,
                    limit: Int,
                ): AppResult<CollaborationSpaceRoomBindingPage> {
                    assertEquals(owner.localDirectoryAccess(), access)
                    reads += after to limit
                    return AppResult.Success(page)
                }
            }
        val loadPage =
            LoadCollaborationSpaceRecoveryPage(
                inventory,
                original.native.setup.sessions,
                original.native.server,
            )
        val loadRoom =
            LoadCollaborationSpaceRecoveryRoom(
                original.bindings,
                inventory,
                original.native.setup.sessions,
                original.native.server,
            )
    }

    companion object {
        private fun id(n: Int) = "60000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    }
}
