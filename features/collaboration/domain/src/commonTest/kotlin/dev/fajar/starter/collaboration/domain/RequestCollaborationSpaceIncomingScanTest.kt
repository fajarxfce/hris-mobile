package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class RequestCollaborationSpaceIncomingScanTest {
    @Test
    fun changedRequestPublishesItsOwnWakeBeforeScheduling() = runTest {
        val f = SpaceIncomingScanFixture()
        val next = f.id(930)
        f.request.schedule = {
            assertEquals(next, f.current().rescanGeneration)
            assertEquals(next, f.request.wake!!.generation)
            AppResult.Success(Unit)
        }
        assertEquals(AppResult.Success(true), f.requestScan(f.access, next))
        assertEquals(1, f.request.writes)
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun exactRepeatedRequestDoesNotWriteOrScheduleAgain() = runTest {
        val f = SpaceIncomingScanFixture()
        repeat(5) {
            assertEquals(AppResult.Success(true), f.requestScan(f.access, f.originalGeneration))
        }
        assertEquals(0, f.request.writes)
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun newerScanRequestPreservesCurrentCursorAndQueuedRooms() = runTest {
        val f = SpaceIncomingScanFixture()
        assertEquals(AppResult.Success(true), f.discover(f.access))
        val before = f.current()
        assertEquals(AppResult.Success(true), f.requestScan(f.access, f.id(930)))
        assertEquals(before.scan, f.current().scan)
        assertEquals(before.rooms, f.current().rooms)
        assertEquals(f.id(930), f.current().rescanGeneration)
    }

    @Test
    fun lostWriteReplyCanBeConfirmedWithoutReissuingTheRequest() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.responseLoss = true
        assertEquals(AppResult.Success(true), f.requestScan(f.access, f.id(930)))
        assertEquals(1, f.request.writes)
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun failedSchedulingDoesNotUndoTheAtomicRescanAndWake() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.schedule = { AppResult.Failed(f.request.base.directory.storageFailure) }
        assertEquals(AppResult.Success(true), f.requestScan(f.access, f.id(930)))
        assertEquals(f.id(930), f.current().rescanGeneration)
        assertEquals(f.id(930), f.request.wake!!.generation)
    }

    @Test
    fun cancellationAfterCommitRetainsTheRequestForStartupRecovery() = runTest {
        val f = SpaceIncomingScanFixture()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["committed"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.requestScan(f.access, f.id(930)) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(f.id(930), f.current().rescanGeneration)
        assertEquals(f.id(930), f.request.wake!!.generation)
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun competingProducerKeepsItsOwnGeneration() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.hooks["write"] = {
            f.set(f.current().copy(rescanGeneration = f.id(931)))
            f.request.wake = CollaborationSpaceIncomingWake(f.id(931))
        }
        assertIs<AppResult.Failed>(f.requestScan(f.access, f.id(930)))
        assertEquals(f.id(931), f.current().rescanGeneration)
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun failedCommitOrReadbackDoesNotClaimAConfirmedRequest() = runTest {
        for (readback in listOf(false, true)) {
            val f = SpaceIncomingScanFixture()
            if (readback)
                f.request.base.failures["readback"] = f.request.base.directory.storageFailure
            else f.request.commit = false
            assertIs<AppResult.Failed>(f.requestScan(f.access, f.id(930)))
            assertEquals(1, f.request.writes)
            assertEquals(0, f.request.schedules)
        }
    }

    @Test
    fun denialOrLateAccountReplacementStopsOldRequestPublication() = runTest {
        for (stage in listOf("denied", "demand", "write", "committed", "readback", "schedule")) {
            val f = SpaceIncomingScanFixture()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.requestScan(f.access, f.id(930)))
            if (stage in listOf("denied", "demand")) assertEquals(0, f.request.writes)
        }
    }

    @Test
    fun stalledStorageHasAFiniteBudgetAndNeverSchedules() = runTest {
        val f = SpaceIncomingScanFixture()
        f.request.base.hooks["demand"] = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.requestScan(f.access, f.id(930))).failure.kind,
        )
        assertEquals(0, f.request.schedules)
    }

    @Test
    fun invalidGenerationCannotTouchLocalStorage() = runTest {
        val f = SpaceIncomingScanFixture()
        assertIs<AppResult.Failed>(f.requestScan(f.access, "bad"))
        assertTrue(f.request.base.calls.isEmpty())
    }
}
