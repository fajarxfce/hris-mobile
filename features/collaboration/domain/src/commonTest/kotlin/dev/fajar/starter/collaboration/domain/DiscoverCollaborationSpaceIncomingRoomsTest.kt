package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DiscoverCollaborationSpaceIncomingRoomsTest {
    @Test
    fun onePageCommitsRoomsAndCursorWithoutSchedulingOrAcknowledging() = runTest {
        val f = SpaceIncomingScanFixture()
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertEquals(listOf<Pair<CollaborationSpaceRoomBindingCursor?, Int>>(null to 4), f.requests)
        assertEquals(4, f.current().rooms.size)
        assertEquals(f.after(4), f.current().scan!!.cursor!!.afterId)
        assertEquals(1, f.request.writes)
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun lostWriteResponseResumesTheRetainedCursorWithoutDuplicatingTasks() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.discover(f.access))
        val queued = f.current().rooms
        val cursor = f.current().scan!!.cursor
        f.nextPage = f.page(0, null)
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertEquals(cursor to 4, f.requests.last())
        assertEquals(queued, f.current().rooms)
        assertNull(f.current().scan)
        assertNull(f.current().rescanGeneration)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun unreadableCommitRetainsBothFactsAndASecondInvocationContinuesFromThem() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.failures["readback"] = f.request.base.directory.storageFailure
        assertIs<AppResult.Failed>(f.discover(f.access))
        assertEquals(4, f.current().rooms.size)
        assertNotNull(f.current().scan)
        f.request.base.failures.clear()
        f.nextPage = f.page(0, null)
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertEquals(4, f.current().rooms.size)
        assertEquals(2, f.requests.size)
    }

    @Test
    fun cancellationAfterCommitKeepsTheAtomicTasksCursorAndWake() = runTest {
        val f = SpaceIncomingScanFixture()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["committed"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.discover(f.access) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(4, f.current().rooms.size)
        assertNotNull(f.current().scan)
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun competingRescanAndWakeSurviveAnOldPageWrite() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.hooks["write"] = {
            f.set(f.current().copy(rescanGeneration = f.id(901)))
            f.request.wake = CollaborationSpaceIncomingWake(f.id(901))
        }
        assertIs<AppResult.Failed>(f.discover(f.access))
        assertEquals(f.id(901), f.current().rescanGeneration)
        assertEquals(f.id(901), f.request.wake!!.generation)
        assertTrue(f.current().rooms.isEmpty())
    }

    @Test
    fun noRequestedScanDoesNotReadInventoryOrWrite() = runTest {
        val f = SpaceIncomingScanFixture()
        f.set(CollaborationSpaceIncomingDemand())
        assertEquals(AppResult.Success(false), f.discover(f.access))
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun fullQueueDoesNotReadPagesOrCreateAnotherOverflowRequest() = runTest {
        val f = SpaceIncomingScanFixture()
        val current =
            f.current()
                .copy(
                    nextPosition = 64,
                    rooms =
                        List(64) {
                            CollaborationSpaceIncomingRoomDemand(
                                f.id(1000 + it),
                                f.id(2000 + it),
                                it.toLong(),
                                null,
                                CollaborationSpaceIncomingPhase.Pending,
                            )
                        },
                )
        f.set(current)
        repeat(3) { assertEquals(AppResult.Success(false), f.discover(f.access)) }
        assertEquals(current, f.current())
        assertTrue(f.requests.isEmpty())
        assertEquals(0, f.request.writes)
    }

    @Test
    fun onlyFreeCapacityIsRequestedAndAnOversizedReplyIsRejected() = runTest {
        val f = SpaceIncomingScanFixture()
        f.set(
            f.current()
                .copy(
                    nextPosition = 63,
                    rooms =
                        List(63) {
                            CollaborationSpaceIncomingRoomDemand(
                                f.id(1000 + it),
                                f.id(2000 + it),
                                it.toLong(),
                                null,
                                CollaborationSpaceIncomingPhase.Pending,
                            )
                        },
                )
        )
        assertIs<AppResult.Failed>(f.discover(f.access))
        assertEquals(listOf<Pair<CollaborationSpaceRoomBindingCursor?, Int>>(null to 1), f.requests)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun failedInventoryReadPreservesDemandAndDoesNotRetryInline() = runTest {
        val f = SpaceIncomingScanFixture()
        val before = f.current()
        f.request.base.failures["inventory"] = f.request.base.directory.storageFailure
        assertIs<AppResult.Failed>(f.discover(f.access))
        assertEquals(before, f.current())
        assertEquals(1, f.requests.size)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun ordinaryCredentialAgeDoesNotHideLocalInventory() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.discover(f.access))
    }

    @Test
    fun denialAndLateAccountReplacementStopAtEveryBoundary() = runTest {
        for (stage in listOf("denied", "demand", "inventory", "write", "committed", "readback")) {
            val f = SpaceIncomingScanFixture()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.discover(f.access))
            assertEquals(f.originalWake, f.request.wake)
            if (stage in listOf("denied", "demand", "inventory")) assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun aStalledInventoryReadEndsWithinOneFiniteTurn() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.hooks["inventory"] = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.discover(f.access)).failure.kind,
        )
        assertEquals(1, f.requests.size)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun returnedBindingsAreCapturedBeforeLaterAdmissionSuspends() = runTest {
        val f = SpaceIncomingScanFixture()
        val bindings = f.nextPage.bindings.toMutableList()
        f.nextPage = f.nextPage.copy(bindings = bindings)
        f.request.base.hooks["inventory"] = {
            f.request.base.directory.beforeAdmission = { bindings.clear() }
        }
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertTrue(bindings.isEmpty())
        assertEquals(4, f.current().rooms.size)
    }

    @Test
    fun existingPendingTasksAreNotResetByAnUnknownStartupScan() = runTest {
        val f = SpaceIncomingScanFixture()
        assertEquals(AppResult.Success(true), f.discover(f.access))
        val old =
            f.current().rooms.map {
                it.copy(phase = CollaborationSpaceIncomingPhase.Pending, headEventId = "\$retained")
            }
        f.set(f.current().copy(rooms = old, scan = null, rescanGeneration = f.id(902)))
        f.nextPage = f.page(position = null)
        assertEquals(AppResult.Success(true), f.discover(f.access))
        assertEquals(old, f.current().rooms)
        assertNull(f.current().rescanGeneration)
    }
}
