package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationSpaceIncomingWakeCompletionTest {
    @Test
    fun emptyCurrentRootDischargesOnlyItsCapturedWake() = runTest {
        val f = idle()
        assertEquals(AppResult.Success(true), f.finish(f.access, f.originalWake))
        assertNull(f.request.wake)
        assertEquals(1, f.acknowledgements)
        assertEquals(0, f.request.writes)
    }

    @Test
    fun queuedRoomsOrEitherRescanStateKeepTheWake() = runTest {
        for (mode in 0..2) {
            val f = SpaceIncomingScanFixture()
            if (mode != 0) assertEquals(AppResult.Success(true), f.discover(f.access))
            if (mode == 2) f.set(f.current().copy(rooms = emptyList()))
            assertEquals(AppResult.Success(false), f.finish(f.access, f.originalWake))
            assertEquals(f.originalWake, f.request.wake)
            assertEquals(0, f.acknowledgements)
        }
    }

    @Test
    fun aProducerRacingCompletionRetainsItsNewQueueAndWake() = runTest {
        val f = idle()
        val next = f.id(920)
        f.request.base.hooks["acknowledge"] = {
            f.set(f.current().copy(rescanGeneration = next))
            f.request.wake = CollaborationSpaceIncomingWake(next)
        }
        assertEquals(AppResult.Success(false), f.finish(f.access, f.originalWake))
        assertEquals(next, f.current().rescanGeneration)
        assertEquals(next, f.request.wake!!.generation)
        assertEquals(1, f.acknowledgements)
    }

    @Test
    fun aLostAcknowledgementReplyIsConfirmedWithoutASecondAck() = runTest {
        val f = idle()
        f.lostWakeReply = true
        assertEquals(AppResult.Success(true), f.finish(f.access, f.originalWake))
        assertEquals(1, f.acknowledgements)
        assertNull(f.request.wake)
    }

    @Test
    fun unreadableAcknowledgementDoesNotClaimSuccess() = runTest {
        val f = idle()
        f.request.base.failures["wake"] = f.request.base.directory.storageFailure
        assertIs<AppResult.Failed>(f.finish(f.access, f.originalWake))
        assertEquals(1, f.acknowledgements)
    }

    @Test
    fun cancellationAfterAcknowledgementDoesNotRecreateTheOldWake() = runTest {
        val f = idle()
        val entered = CompletableDeferred<Unit>()
        f.request.base.hooks["wakeCommitted"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = async { f.finish(f.access, f.originalWake) }
        entered.await()
        job.cancelAndJoin()
        assertNull(f.request.wake)
        assertEquals(1, f.acknowledgements)
    }

    @Test
    fun anotherNativeRootCannotDischargeThisGlobalWorkKey() = runTest {
        for (root in listOf(null, CollaborationSpaceRoot("40000000-0000-4000-8000-000000000999"))) {
            val f = idle()
            f.root = root
            assertIs<AppResult.Failed>(f.finish(f.access, f.originalWake))
            assertEquals(0, f.acknowledgements)
            assertEquals(f.originalWake, f.request.wake)
        }
    }

    @Test
    fun rootReplacementAfterQueueReadStopsAcknowledgement() = runTest {
        val f = idle()
        f.request.base.hooks["demand"] = { f.root = CollaborationSpaceRoot(f.id(999)) }
        assertIs<AppResult.Failed>(f.finish(f.access, f.originalWake))
        assertEquals(0, f.acknowledgements)
    }

    @Test
    fun denialAndLateAccountReplacementCannotFinishTheOldScope() = runTest {
        for (stage in listOf("denied", "demand", "acknowledge", "wakeCommitted", "wake")) {
            val f = idle()
            if (stage == "denied") f.request.base.directory.deny()
            else f.request.base.hooks[stage] = { f.request.base.native.setup.session = null }
            assertIs<AppResult.Failed>(f.finish(f.access, f.originalWake))
            if (stage in listOf("denied", "demand")) assertEquals(0, f.acknowledgements)
        }
    }

    @Test
    fun expiredCredentialsDoNotHideCleanLocalCompletion() = runTest {
        val f = idle()
        f.request.base.native.setup.now += 365.days
        assertEquals(AppResult.Success(true), f.finish(f.access, f.originalWake))
    }

    @Test
    fun stalledRootReadHasAFiniteCancelledBudget() = runTest {
        val f = idle()
        f.request.base.hooks["root"] = { awaitCancellation() }
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.finish(f.access, f.originalWake)).failure.kind,
        )
        assertEquals(0, f.acknowledgements)
    }

    @Test
    fun ensuringAndReadingWakeNeverScheduleOrReplaceAPendingGeneration() = runTest {
        val f = idle()
        repeat(3) { assertEquals(AppResult.Success(f.originalWake), f.ensureWake()) }
        assertEquals(AppResult.Success(f.originalWake), f.readWake())
        assertEquals(0, f.wakeRequests)
        assertEquals(0, f.request.schedules)
        f.request.wake = null
        assertIs<AppResult.Success<CollaborationSpaceIncomingWake>>(f.ensureWake())
        assertEquals(1, f.wakeRequests)
    }

    @Test
    fun resumingOnlySchedulesExistingWorkAndKeepsItsGeneration() = runTest {
        val f = idle()
        assertEquals(AppResult.Success(true), f.resume())
        assertEquals(f.originalWake, f.request.wake)
        assertEquals(1, f.request.schedules)
        f.request.wake = null
        assertEquals(AppResult.Success(false), f.resume())
        assertEquals(1, f.request.schedules)
    }

    @Test
    fun failedOrStalledSchedulingRetainsTheWakeForAnotherTrigger() = runTest {
        for (stall in listOf(false, true)) {
            val f = idle()
            f.request.schedule = {
                if (stall) awaitCancellation()
                else AppResult.Failed(f.request.base.directory.storageFailure)
            }
            assertIs<AppResult.Failed>(f.resume())
            assertEquals(f.originalWake, f.request.wake)
            assertEquals(1, f.request.schedules)
        }
    }

    private fun idle() =
        SpaceIncomingScanFixture().also { it.set(CollaborationSpaceIncomingDemand()) }
}
