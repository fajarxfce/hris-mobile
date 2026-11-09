@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.CollaborationRecoveryWake
import dev.fajar.starter.collaboration.domain.usecases.RequestCollaborationRecovery
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.fixtures.RecoveryWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.SyncTrigger
import dev.fajar.starter.worker.ForegroundWorkScheduler
import dev.fajar.starter.worker.RequestedSyncWorker
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationRecoverySchedulingTest {
    @Test
    fun delayedOsRequestCannotRestartASweepAlreadyCompletedInTheForeground() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, count = 2, retainedRequest = false)
        val worker = f.worker()
        assertIs<AppResult.Success<Unit>>(RequestCollaborationRecovery(f.wakes, f.schedules)())
        assertEquals(SyncResult.Complete, worker()) // First company schedules continuation.
        assertNotNull(f.wake)
        assertEquals(SyncResult.Complete, worker()) // Foreground consumes that continuation.
        assertNull(f.wake)
        assertEquals(2, f.pages)
        assertEquals(1, f.wakeWrites)

        // The same continuation also left an OS request. Its later delivery is not new intent.
        repeat(3) { assertEquals(SyncResult.Complete, worker()) }
        assertEquals(2, f.pages, "A stale request must perform no remote discovery")
        assertEquals(1, f.wakeWrites, "Consumers must not manufacture another recovery wake")
        assertNull(f.wake)

        assertIs<AppResult.Success<Unit>>(RequestCollaborationRecovery(f.wakes, f.schedules)())
        repeat(2) { assertEquals(SyncResult.Complete, worker()) }
        assertEquals(4, f.pages, "A genuine later request must still cover both companies")
        assertNull(f.wake)
    }

    @Test
    fun osAndForegroundDeliveriesDrainOneRequestWithoutFeedingEachOther() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, count = 2, retainedRequest = false)
        val requests = ForegroundWorkScheduler(setOf(CollaborationRecoveryWake.WORK_KEY))
        var osPending = false
        f.onSchedule = {
            osPending = true
            assertTrue(requests.notify(it))
        }
        val worker = f.worker()
        val host = RequestedSyncWorker(listOf(worker), requests).start(backgroundScope)
        runCurrent()
        assertIs<AppResult.Success<Unit>>(RequestCollaborationRecovery(f.wakes, f.schedules)())
        runCurrent()
        assertEquals(2, f.pages)
        assertNull(f.wake)
        assertTrue(osPending)
        osPending = false
        assertEquals(SyncResult.Complete, worker())
        runCurrent()
        assertFalse(osPending, "A stale OS delivery must not enqueue another OS/foreground pass")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, f.pages)
        assertEquals(1, f.wakeWrites)
        host.cancelAndJoin()
        requests.close()
    }

    @Test
    fun scheduledDiscoveryRetainsItsWakeAcrossCancellationAndRequestedRecovery() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, count = 1, retainedRequest = false)
        val entered = CompletableDeferred<Unit>()
        f.onCompany = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val worker = f.worker()
        val periodic = launch { worker(SyncTrigger.Scheduled) }
        entered.await()
        val original = assertNotNull(f.wake)
        periodic.cancelAndJoin()
        assertEquals(original, f.wake)
        f.onCompany = { SyncResult.Complete }
        // An interrupted company remains uncertain for one traversal before a complete retry.
        assertIs<SyncResult.Retry>(worker())
        assertEquals(SyncResult.Complete, worker())
        assertNull(f.wake)
        assertEquals(1, f.wakeWrites)
        val coveredPages = f.pages
        assertEquals(SyncResult.Complete, worker())
        assertEquals(coveredPages, f.pages)
        assertEquals(SyncResult.Complete, worker(SyncTrigger.Scheduled))
        assertEquals(2, f.wakeWrites, "A later periodic cycle still discovers incoming work")
        assertNull(f.wake)
    }

    @Test
    fun aFailedScheduledSweepRemainsEligibleWithoutManufacturingANewGeneration() = runTest {
        val f = RecoveryWorkerFixture(testScheduler, count = 1, retainedRequest = false)
        f.onCompany = { SyncResult.Retry(Failure(FailureKind.Network, "Offline")) }
        val worker = f.worker()
        assertIs<SyncResult.Retry>(worker(SyncTrigger.Scheduled))
        val original = assertNotNull(f.wake)
        f.onCompany = {
            assertEquals(original, f.wake)
            SyncResult.Complete
        }
        assertEquals(SyncResult.Complete, worker())
        assertNull(f.wake)
        assertEquals(1, f.wakeWrites)
    }
}
