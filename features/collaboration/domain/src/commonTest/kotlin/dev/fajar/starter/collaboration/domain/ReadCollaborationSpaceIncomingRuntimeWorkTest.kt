package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReadCollaborationSpaceIncomingRuntimeWorkTest {
    private val source =
        CollaborationSpaceIncomingSource("1".repeat(64), "@user:example.test", "DEVICE")

    @Test
    fun initialLocalReadDoesNotLookUpRuntimeButCanDiscoverPhysicalDemand() = runTest {
        val f = fixture()
        f.installedSource = null
        val work =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertEquals(1, work.physicalReady.size)
        assertEquals(0, f.sourceReads)
        assertEquals(listOf("demand"), f.request.base.calls)
    }

    @Test
    fun runtimeReadSelectsItsOwnedPhysicalWorkWithoutStartingOrObservingSync() = runTest {
        val f = fixture()
        val work =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(
                    f.read(f.request.provider.runtime)
                )
                .value
        assertEquals(source.storeId, work.physicalReady.single().sourceId)
        assertTrue(f.sourceReads > 0)
        assertEquals(listOf("demand"), f.request.base.calls)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun anotherOriginalsTasksRemainPendingWithoutBeingSelected() = runTest {
        val f = fixture()
        f.installedSource = source.copy(storeId = "2".repeat(64))
        val work =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(
                    f.read(f.request.provider.runtime)
                )
                .value
        assertTrue(work.roomsPending)
        assertTrue(work.physicalReady.isEmpty())
        assertTrue(work.ready.isEmpty())
        assertEquals(1, work.snapshot?.demand?.physicalRooms?.size)
    }

    @Test
    fun absentRuntimeDoesNotReadDemandToRepairOrAcquireIt() = runTest {
        val f = fixture()
        f.installedSource = null
        assertEquals(
            FailureKind.Unavailable,
            assertIs<AppResult.Failed>(f.read(f.request.provider.runtime)).failure.kind,
        )
        assertEquals(0, f.request.reads)
    }

    @Test
    fun sourceOrNativeParentReplacementWhileReadingCannotPublishOldSelection() = runTest {
        for (native in listOf(false, true)) {
            val f = fixture()
            f.request.base.hooks["demand"] = {
                if (native) {
                    val setup = f.request.base.native.setup
                    val current = assertNotNull(setup.session)
                    setup.session =
                        current.copy(binding = current.binding.copy(serverId = f.id(990)))
                } else f.installedSource = source.copy(matrixDeviceId = "CHANGED")
            }
            assertEquals(
                if (native) FailureKind.Unauthorized else FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.read(f.request.provider.runtime)).failure.kind,
            )
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun confirmedDenialWinsOverAbsentRuntimeAndLateStorageFailure() = runTest {
        for (late in listOf(false, true)) {
            val f = fixture()
            if (late) {
                f.request.base.hooks["demand"] = { f.request.base.directory.deny() }
                f.request.base.failures["demand"] = f.request.base.directory.storageFailure
            } else {
                f.request.base.directory.deny()
                f.installedSource = null
            }
            assertEquals(
                f.request.base.directory.denied,
                assertIs<AppResult.Failed>(f.read(f.request.provider.runtime)).failure,
            )
        }
    }

    @Test
    fun ordinaryCredentialAgeDoesNotHideOwnedRuntimeWork() = runTest {
        val f = fixture()
        f.request.base.native.setup.now += 365.days
        assertIs<AppResult.Success<*>>(f.read(f.request.provider.runtime))
    }

    @Test
    fun pendingReadIsCancelledAtItsLocalBudgetWithoutWritesOrNetworkRecovery() = runTest {
        val f = fixture()
        var stopped = false
        f.request.base.hooks["demand"] = {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        val started = currentTime
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.read(f.request.provider.runtime)).failure.kind,
        )
        assertEquals(5_000L, currentTime - started)
        assertTrue(stopped)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun invalidRuntimeOperationCannotReadSourceOrDemand() = runTest {
        val f = fixture()
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read(f.request.provider.runtime.copy(operationId = "bad")))
                .failure
                .kind,
        )
        assertEquals(0, f.sourceReads)
        assertEquals(0, f.request.reads)
    }

    private fun fixture() =
        SpaceIncomingScanFixture().also { f ->
            f.installedSource = source
            f.set(
                CollaborationSpaceIncomingDemand(
                    nextPosition = 1,
                    physicalRooms =
                        listOf(
                            CollaborationSpaceIncomingPhysicalRoomDemand(
                                source.storeId,
                                "!room:example.test",
                                f.id(901),
                                0,
                                null,
                                CollaborationSpaceIncomingMembership.Joined,
                                false,
                            )
                        ),
                    sources = listOf(CollaborationSpaceIncomingSourceProgress(source, "position")),
                )
            )
        }
}
