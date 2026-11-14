@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceInboxRepairWorkerTest {
    @Test
    fun completionOnlyUpdatesDoNotRestartAFailedCommandPass() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onPage = { _, _ -> AppResult.Failed(f.storageFailure) }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        val parked = assertNotNull(f.progress)
        assertEquals(1, f.pages.size)
        val reads = f.progressReads
        val changes = f.changes.toList()
        f.demand.value =
            AppResult.Success(CollaborationSpaceInboxRepairSignals(parked.latestDemand, f.id(900)))
        runCurrent()
        assertEquals(1, f.pages.size)
        assertEquals(reads, f.progressReads)
        assertEquals(changes, f.changes)
        f.request()
        runCurrent()
        assertEquals(2, f.pages.size)
        assertEquals(1, f.requestStarts)
        job.cancelAndJoin()
        assertEquals(1, f.commandStops)
        assertEquals(1, f.requestStops)
    }

    @Test
    fun unchangedReopenKeepsCompletedDemandAndDoesNotReadAnotherRepairPage() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val first = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        val completed = assertNotNull(f.progress)
        assertEquals(completed.latestDemand, completed.completedDemand)
        first.cancelAndJoin()
        val pages = f.pages.toList()
        val second = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(completed, f.progress)
        assertEquals(pages, f.pages)
        assertEquals(
            0,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.Request },
        )
        assertEquals(
            2,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        second.cancelAndJoin()
        assertEquals(2, f.commandStops)
        assertEquals(2, f.requestStops)
    }

    @Test
    fun aLiveTargetOverflowStillCreatesOneRealRequestAfterBootstrap() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        f.records.value =
            AppResult.Success(
                (0 until 128).map { n ->
                    f.queued.copy(intent = f.queued.intent.copy(id = f.id(1000 + n)))
                }
            )
        runCurrent()
        f.records.value =
            AppResult.Success(
                (0 until 128).map { n ->
                    f.sent.copy(intent = f.sent.intent.copy(id = f.id(2000 + n)))
                }
            )
        runCurrent()
        assertEquals(128, f.lookups.size)
        assertEquals(2, f.pages.size)
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.Request },
        )
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        assertEquals(f.progress?.latestDemand, f.progress?.completedDemand)
        job.cancelAndJoin()
    }

    @Test
    fun aPendingOnlyStartupPassCompletesAndStaysIdleWithoutTimersOrDuplicateObservers() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(listOf<String?>(null), f.pages)
        assertEquals(f.progress?.latestDemand, f.progress?.completedDemand)
        val reads = f.progressReads
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(reads, f.progressReads)
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        assertEquals(1, f.commandStarts)
        assertEquals(1, f.requestStarts)
        job.cancelAndJoin()
        assertEquals(1, f.commandStops)
        assertEquals(1, f.requestStops)
    }

    @Test
    fun observedTerminalAndRetirementUseExactLookupWithoutRepeatingTheFiniteScan() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        f.reconciled.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        runCurrent()
        assertTrue(f.lookups.isEmpty())
        f.onReconcile = {
            f.references.remove(it.intent.id)
            AppResult.Success(true)
        }
        f.records.value = AppResult.Success(listOf(f.sent))
        runCurrent()
        f.records.value = AppResult.Success(emptyList())
        runCurrent()
        assertEquals(2, f.lookups.size)
        assertTrue(
            f.lookups.all { it == (f.reference.intent.conversationId to f.reference.intent.id) }
        )
        assertEquals(listOf(f.reference.intent.id), f.reconciled)
        assertEquals(listOf<String?>(null), f.pages)
        job.cancelAndJoin()
    }

    @Test
    fun anUnobservedFastArchiveStillRepairsThroughItsDurableRequestWithAnEqualEmptyInventory() =
        runTest {
            val f = SpaceInboxRepairWorkerFixture()
            val job = backgroundScope.launch { f.worker()(f.access).collect() }
            runCurrent()
            f.reconciled.clear()
            f.onReconcile = {
                f.references.remove(it.intent.id)
                AppResult.Success(true)
            }
            // Insert, send and archive were conflated before the active list query; it stays empty.
            f.request()
            runCurrent()
            assertEquals(listOf(f.reference.intent.id), f.reconciled)
            assertEquals(2, f.pages.size)
            assertTrue(f.lookups.isEmpty())
            assertEquals(f.progress?.latestDemand, f.progress?.completedDemand)
            val count = f.pages.size
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(count, f.pages.size)
            job.cancelAndJoin()
        }

    @Test
    fun aRequestDuringTheCapturedPassIsProcessedOnceAfterThatPass() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        var later: String? = null
        f.afterChange = { change ->
            if (change is CollaborationSpaceInboxRepairProgressChange.Advance && later == null)
                later = f.request()
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertNotNull(later)
        assertEquals(later, f.progress?.completedDemand)
        assertEquals(2, f.pages.size)
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        job.cancelAndJoin()
    }

    @Test
    fun aFailedPageDoesNotAdvanceOrRetryFromItsOwnStartupRequestNotification() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onReconcile = { AppResult.Failed(f.storageFailure) }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(AppResult.Failed(f.storageFailure), values.last())
        assertEquals(1, f.reconciled.size)
        assertFalse(f.changes.any { it is CollaborationSpaceInboxRepairProgressChange.Advance })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.reconciled.size)
        f.onReconcile = { AppResult.Success(false) }
        f.request()
        runCurrent()
        assertEquals(AppResult.Success(Unit), values.last())
        assertEquals(f.progress?.latestDemand, f.progress?.completedDemand)
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        job.cancelAndJoin()
    }

    @Test
    fun cancellationWaitsForAStoppedPageAndNeverSavesItsLateCompletion() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onReconcile = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(true)
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        entered.await()
        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertFalse(f.changes.any { it is CollaborationSpaceInboxRepairProgressChange.Advance })
        assertEquals(1, f.commandStops)
        assertEquals(1, f.requestStops)
    }

    @Test
    fun repeatedGenerationReplacementHasAFiniteRestartBudgetAndLeavesResumableProgress() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onPage = { after, _ ->
            AppResult.Success(
                if (after != null) CollaborationSpaceInboxRepairPage.Restart
                else
                    CollaborationSpaceInboxRepairPage.Items(
                        CollaborationSpaceInboxVersion(f.id(90), 1, 1),
                        listOf(f.reference),
                        "next",
                    )
            )
        }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertIs<AppResult.Failed>(values.last())
        assertEquals(
            2,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.Restart },
        )
        assertEquals(6, f.pages.size)
        assertNotNull(f.progress?.pass)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(6, f.pages.size)
        job.cancelAndJoin()
    }

    @Test
    fun theTurnDeadlineCancelsStalledWorkWithoutInventingAnotherRequest() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        var cancelled = false
        f.onReconcile = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(cancelled)
        assertIs<AppResult.Failed>(values.last())
        assertEquals(1, f.reconciled.size)
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        assertNotNull(f.progress?.pass)
        job.cancelAndJoin()
    }

    @Test
    fun restartingAnOwnerResumesTheSavedCursorBeforeProcessingItsLaterRequest() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        var failSecond = true
        f.onPage = { after, _ ->
            if (after == "saved" && failSecond) AppResult.Failed(f.storageFailure)
            else
                AppResult.Success(
                    CollaborationSpaceInboxRepairPage.Items(
                        CollaborationSpaceInboxVersion(f.id(90), 1, 1),
                        listOf(f.reference),
                        if (after == null) "saved" else null,
                    )
                )
        }
        val first = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(listOf(null, "saved"), f.pages)
        assertEquals("saved", f.progress?.pass?.after)
        first.cancelAndJoin()
        f.pages.clear()
        failSecond = false
        val second = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(listOf<String?>("saved"), f.pages)
        val resumed = f.progress
        f.request()
        runCurrent()
        assertEquals(listOf("saved", null, "saved"), f.pages)
        assertNotEquals(resumed?.latestDemand, f.progress?.latestDemand)
        assertEquals(f.progress?.latestDemand, f.progress?.completedDemand)
        second.cancelAndJoin()
        assertEquals(2, f.commandStarts)
        assertEquals(2, f.commandStops)
        assertEquals(2, f.requestStops)
    }

    @Test
    fun anExcessivePageSourceStopsAtTheTurnBudgetWithoutLosingItsSavedCursor() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onPage = { after, _ ->
            val position = (after?.toInt() ?: 0) + 1
            val original =
                f.reference.copy(
                    intent = f.reference.intent.copy(id = f.id(1000 + position)),
                    insertedAtRevision = position.toLong(),
                )
            AppResult.Success(
                CollaborationSpaceInboxRepairPage.Items(
                    CollaborationSpaceInboxVersion(f.id(90), 512, 1),
                    listOf(original),
                    position.toString(),
                )
            )
        }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(256, f.pages.size)
        assertEquals("256", f.progress?.pass?.after)
        assertIs<AppResult.Failed>(values.last())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(256, f.pages.size)
        assertEquals(
            1,
            f.changes.count { it is CollaborationSpaceInboxRepairProgressChange.EnsureInitialized },
        )
        job.cancelAndJoin()
    }
}
