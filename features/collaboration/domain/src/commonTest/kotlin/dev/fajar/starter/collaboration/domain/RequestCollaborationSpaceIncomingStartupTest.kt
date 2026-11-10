package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RequestCollaborationSpaceIncomingStartupTest {
    @Test
    fun warmResumeLeavesAbsentOrIdleDemandAlone() = runTest {
        for (absent in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            f.set(CollaborationSpaceIncomingDemand())
            if (absent) f.request.snapshot = null
            f.request.wake = null
            val before = f.request.snapshot
            repeat(3) {
                assertEquals(
                    AppResult.Success(true),
                    f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Resume),
                )
            }
            assertEquals(before, f.request.snapshot)
            assertNull(f.request.wake)
            assertEquals(0, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun warmResumeSchedulesPendingWorkWithoutReplacingItsBackoffOrWake() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.queued()
        assertEquals(
            AppResult.Success(true),
            f.defer(
                f.access,
                f.current().rooms.single(),
                Failure(FailureKind.Unavailable, "Fixture key pending"),
            ),
        )
        val before = f.request.snapshot
        val wake = f.request.wake
        val writes = f.request.writes
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Resume),
        )
        assertEquals(before, f.request.snapshot)
        assertEquals(wake, f.request.wake)
        assertEquals(writes, f.request.writes)
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun repeatedStartupBetweenPagesConvergesWithoutRequestingAnotherPass() = runTest {
        val f = SpaceIncomingScanFixture()
        val pages =
            listOf(
                f.page(),
                f.page(position = 8).copy(bindings = List(4) { f.binding(4 + it) }),
                f.page(count = 0, position = null),
            )
        for ((index, page) in pages.withIndex()) {
            f.nextPage = page
            assertEquals(
                AppResult.Success(true),
                f.requestScan(
                    f.access,
                    f.id(940 + index),
                    CollaborationSpaceIncomingScanReason.Startup,
                ),
            )
            assertEquals(AppResult.Success(true), f.discover(f.access))
        }
        assertEquals(8, f.current().rooms.size)
        assertEquals(3, f.requests.size)
        assertNull(f.current().scan)
        assertNull(f.current().rescanGeneration)
        val before = f.current()
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(943), CollaborationSpaceIncomingScanReason.Startup),
        )
        assertEquals(before, f.current())
        f.set(before.copy(rooms = emptyList()))
        assertEquals(AppResult.Success(true), f.finish(f.access, f.originalWake))
        assertNull(f.request.wake)
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(944), CollaborationSpaceIncomingScanReason.Startup),
        )
        assertEquals(f.id(944), f.current().rescanGeneration)
    }

    @Test
    fun repeatedForegroundEntriesPreserveTheActiveInventoryAndFifo() = runTest {
        val f = SpaceIncomingScanFixture()
        assertEquals(AppResult.Success(true), f.discover(f.access))
        val before = f.current()
        val wake = f.request.wake
        val writes = f.request.writes
        repeat(5) { index ->
            assertEquals(
                AppResult.Success(true),
                f.requestScan(
                    f.access,
                    f.id(940 + index),
                    CollaborationSpaceIncomingScanReason.Startup,
                ),
            )
            assertEquals(before, f.current())
            assertEquals(wake, f.request.wake)
        }
        assertEquals(writes, f.request.writes)
        assertEquals(5, f.request.schedules)
    }

    @Test
    fun pendingRoomKeepsItsPhaseAndMissingKeyBackoff() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.queued()
        assertEquals(
            AppResult.Success(true),
            f.defer(
                f.access,
                f.current().rooms.single(),
                Failure(FailureKind.Unavailable, "Waiting for fixture key"),
            ),
        )
        val before = f.current()
        assertNotNull(before.rooms.single().retry)
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup),
        )
        assertEquals(before, f.current())
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun idleForegroundEntryRetainsOneNewScanAndWake() = runTest {
        val f = SpaceIncomingScanFixture()
        f.set(CollaborationSpaceIncomingDemand())
        f.request.wake = null
        val next = f.id(940)
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, next, CollaborationSpaceIncomingScanReason.Startup),
        )
        assertEquals(next, f.current().rescanGeneration)
        assertEquals(CollaborationSpaceIncomingWake(next), f.request.wake)
        assertEquals(1, f.request.writes)
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun absentDemandCanConfirmALostStartupWriteReply() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.snapshot = null
        f.request.wake = null
        f.request.responseLoss = true
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup),
        )
        assertEquals(f.id(940), f.current().rescanGeneration)
        assertEquals(1, f.request.writes)
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun failedOrStalledSchedulerDoesNotReplaceUnfinishedWork() = runTest {
        for (stalled in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            val before = f.request.snapshot
            f.request.schedule = {
                if (stalled) awaitCancellation()
                else AppResult.Failed(f.request.base.directory.storageFailure)
            }
            assertEquals(
                AppResult.Success(true),
                f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup),
            )
            assertEquals(before, f.request.snapshot)
            assertEquals(f.originalWake, f.request.wake)
            assertEquals(0, f.request.writes)
            assertEquals(1, f.request.schedules)
        }
    }

    @Test
    fun denialAndLateParentReplacementStopStartupConfirmation() = runTest {
        for (stage in listOf("denied", "demand", "schedule")) {
            val f = SpaceIncomingScanFixture()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(
                f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup)
            )
            assertEquals(0, f.request.writes)
            assertEquals(if (stage == "schedule") 1 else 0, f.request.schedules)
        }
    }

    @Test
    fun malformedOrFailedDemandIsNeverUsedAsResumeAuthority() = runTest {
        for (failed in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            if (failed) f.request.base.failures["demand"] = f.request.base.directory.storageFailure
            else f.set(f.current().copy(rescanGeneration = "invalid"))
            assertIs<AppResult.Failed>(
                f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup)
            )
            assertEquals(0, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun cancelledResumePreservesTheOriginalDurableWake() = runTest {
        val f = SpaceIncomingScanFixture()
        val entered = CompletableDeferred<Unit>()
        f.request.schedule = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async {
            f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup)
        }
        entered.await()
        work.cancelAndJoin()
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(f.originalGeneration, f.current().rescanGeneration)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun completionRacingWithResumeDoesNotRecreateAnAcknowledgedWake() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.hooks["schedule"] = {
            f.set(CollaborationSpaceIncomingDemand())
            f.request.wake = null
        }
        assertEquals(
            AppResult.Success(true),
            f.requestScan(f.access, f.id(940), CollaborationSpaceIncomingScanReason.Startup),
        )
        assertNull(f.request.wake)
        assertEquals(CollaborationSpaceIncomingDemand(), f.current())
        assertEquals(0, f.request.writes)
    }
}
