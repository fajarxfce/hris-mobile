package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIncomingSchedulingTest {
    @Test
    fun deferredRoomPreservesEachPhaseAndLetsLaterRoomsTakeTheirTurn() = runTest {
        for (phase in CollaborationSpaceIncomingPhase.entries) {
            val f = populated(phase)
            val captured = f.current().rooms.take(4)
            val later = f.current().rooms.drop(4)
            for (room in captured) assertEquals(AppResult.Success(true), f.defer(f.access, room))
            assertEquals(later, f.current().rooms.take(2))
            assertEquals(
                captured,
                f.current().rooms.drop(2).mapIndexed { i, room -> room.copy(position = i.toLong()) },
            )
            assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L), f.current().rooms.map { it.position })
            assertEquals(f.originalWake, f.request.wake)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun lostDeferralReplyAndExactReplayDoNotRepeatedlyMoveTheRoom() = runTest {
        val f = populated()
        val old = f.current().rooms.first()
        f.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.defer(f.access, old))
        val after = f.current()
        repeat(3) { assertEquals(AppResult.Success(true), f.defer(f.access, old)) }
        assertEquals(after, f.current())
        assertEquals(1, f.request.writes)
    }

    @Test
    fun absentAndNewerGenerationAreLeftForTheirOwnTurn() = runTest {
        for (absent in listOf(false, true)) {
            val f = populated()
            val old = f.current().rooms.first()
            f.set(
                f.current()
                    .copy(
                        rooms =
                            if (absent) emptyList() else listOf(old.copy(generation = f.id(990)))
                    )
            )
            val before = f.current()
            assertEquals(AppResult.Success(false), f.defer(f.access, old))
            assertEquals(before, f.current())
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun sameGenerationWithChangedPhaseOrHeadIsNotAnExactReplay() = runTest {
        for (head in listOf(false, true)) {
            val f = populated()
            val old = f.current().rooms.first()
            val altered =
                if (head) old.copy(headEventId = "\$other")
                else old.copy(phase = CollaborationSpaceIncomingPhase.Latest)
            f.set(f.current().copy(rooms = listOf(altered)))
            assertIs<AppResult.Failed>(f.defer(f.access, old))
            assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun concurrentRequestAndWakeCannotBeOverwrittenByDeferral() = runTest {
        val f = populated()
        val old = f.current().rooms.first()
        f.request.base.hooks["write"] = {
            f.set(f.current().copy(rooms = listOf(old.copy(generation = f.id(991)))))
            f.request.wake = CollaborationSpaceIncomingWake(f.id(991))
        }
        assertIs<AppResult.Failed>(f.defer(f.access, old))
        assertEquals(f.id(991), f.current().rooms.single().generation)
        assertEquals(f.id(991), f.request.wake!!.generation)
    }

    @Test
    fun uncertainReadbackDoesNotEraseTheRetainedDeferral() = runTest {
        val f = populated()
        val old = f.current().rooms.first()
        f.request.base.failures["readback"] = f.request.base.directory.storageFailure
        assertIs<AppResult.Failed>(f.defer(f.access, old))
        assertEquals(old.copy(position = 6), f.current().rooms.last())
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun cancelledDeferralAfterCommitKeepsItsPositionAndWake() = runTest {
        val f = populated()
        val old = f.current().rooms.first()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["committed"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.defer(f.access, old) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(old.copy(position = 6), f.current().rooms.last())
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun stalledDeferralReadEndsWithoutInlineRetry() = runTest {
        val f = populated()
        f.request.base.hooks["demand"] = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.defer(f.access, f.current().rooms.first())).failure.kind,
        )
        assertEquals(1, f.request.reads)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun deferralStopsAfterDenialOrAccountReplacementAtEachBoundary() = runTest {
        for (stage in listOf("denied", "demand", "write", "committed", "readback")) {
            val f = populated()
            val old = f.current().rooms.first()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.defer(f.access, old))
            assertEquals(f.originalWake, f.request.wake)
        }
    }

    @Test
    fun localDemandReadDoesNotScheduleOrAcquireAnything() = runTest {
        val f = populated()
        assertEquals(
            f.request.snapshot,
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access))
                .value
                .snapshot,
        )
        assertEquals(listOf("demand"), f.request.base.calls)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun absentLocalDemandIsReadableWithoutCreatingIt() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.snapshot = null
        val result =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertNull(result.snapshot)
        assertTrue(result.ready.isEmpty())
        assertFalse(result.scanPending)
        assertFalse(result.roomsPending)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun expiredCredentialsDoNotHideLocalReadOrDeferral() = runTest {
        val f = populated()
        f.request.base.native.setup.now += 365.days
        assertIs<AppResult.Success<*>>(f.read(f.access))
        assertEquals(AppResult.Success(true), f.defer(f.access, f.current().rooms.first()))
    }

    @Test
    fun localDemandIsCapturedBeforeLaterAdmissionCanMutateTheList() = runTest {
        val f = populated()
        val mutable = f.current().rooms.toMutableList()
        f.set(f.current().copy(rooms = mutable))
        f.request.base.hooks["demand"] = {
            f.request.base.directory.beforeAdmission = { mutable.clear() }
        }
        val read =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access))
                .value
                .snapshot!!
        assertTrue(mutable.isEmpty())
        assertEquals(6, read.demand.rooms.size)
    }

    @Test
    fun foreignScanCursorFailsAnOtherwiseOwnedLocalRead() = runTest {
        val f = populated()
        f.set(
            f.current()
                .copy(
                    scan =
                        CollaborationSpaceIncomingScan(
                            f.originalGeneration,
                            CollaborationSpaceRoomBindingCursor(
                                f.boundary.copy(spaceId = f.id(993)),
                                f.after(1),
                            ),
                        )
                )
        )
        assertIs<AppResult.Failed>(f.read(f.access))
        assertEquals(0, f.request.writes)
    }

    @Test
    fun deniedOrReplacedAccountCannotPublishLocalDemand() = runTest {
        for (late in listOf(false, true)) {
            val f = populated()
            if (late)
                f.request.base.hooks["demand"] = { f.request.base.native.setup.session = null }
            else f.request.base.directory.deny()
            assertIs<AppResult.Failed>(f.read(f.access))
        }
    }

    @Test
    fun stalledLocalReadTimesOutWithoutChangingItsWake() = runTest {
        val f = populated()
        f.request.base.hooks["demand"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read(f.access)).failure.kind)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun ownedTurnLimitsReadyRoomsAndSkipsDeferredRoomsWithoutReordering() = runTest {
        val f = populated()
        val first =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertEquals(f.current().rooms.take(4), first.ready)
        assertTrue(first.roomsPending)
        assertTrue(first.scanPending)
        val now = f.request.base.native.clock.now().toEpochMilliseconds()
        f.set(
            f.current()
                .copy(
                    rooms =
                        f.current().rooms.mapIndexed { i, room ->
                            if (i < 2)
                                room.copy(
                                    retry = CollaborationSpaceIncomingRetry(now, now + 30_000, 1)
                                )
                            else room
                        }
                )
        )
        val deferred =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertEquals(f.current().rooms.drop(2), deferred.ready)
        assertEquals(0, f.request.writes)
        assertEquals(f.originalWake, f.request.wake)
    }

    @Test
    fun fullQueueKeepsRescanDurableWithoutOfferingAnInventoryRead() = runTest {
        val f = populated()
        val room = f.current().rooms.first()
        f.set(
            f.current()
                .copy(
                    nextPosition = 64,
                    rooms =
                        List(64) { index ->
                            room.copy(
                                conversationId = f.id(3000 + index),
                                generation = f.id(4000 + index),
                                position = index.toLong(),
                            )
                        },
                )
        )
        val read =
            assertIs<AppResult.Success<CollaborationSpaceIncomingWork>>(f.read(f.access)).value
        assertFalse(read.scanPending)
        assertNotNull(read.snapshot!!.demand.rescanGeneration)
        assertEquals(4, read.ready.size)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun invalidClockDoesNotPublishAFalseIdleTurn() = runTest {
        val f = populated()
        f.request.base.native.setup.now = kotlin.time.Instant.fromEpochMilliseconds(-1)
        assertEquals(
            FailureKind.Validation,
            assertIs<AppResult.Failed>(f.read(f.access)).failure.kind,
        )
        assertEquals(0, f.request.writes)
        assertEquals(f.originalWake, f.request.wake)
    }

    private fun populated(
        phase: CollaborationSpaceIncomingPhase = CollaborationSpaceIncomingPhase.Pending
    ) =
        SpaceIncomingScanFixture().also { f ->
            f.set(
                f.current()
                    .copy(
                        nextPosition = 6,
                        rooms =
                            List(6) {
                                CollaborationSpaceIncomingRoomDemand(
                                    f.id(1000 + it),
                                    f.id(2000 + it),
                                    it.toLong(),
                                    "\$head-$it",
                                    phase,
                                )
                            },
                    )
            )
        }
}
