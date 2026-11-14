@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationSpaceInboxHistoryTurnWorker
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceInboxRepairLifetimeTest {
    @Test
    fun historySuccessCannotAcknowledgeCommandInitializationBeforeItsInventoryArrives() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val ready = CompletableDeferred<Unit>()
        f.commandSource = {
            flow {
                ready.await()
                emitAll(f.records)
            }
        }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(outcomes) }
        runCurrent()
        assertTrue(f.historyCalls.isNotEmpty())
        assertTrue(outcomes.isEmpty())
        assertNull(f.progress)
        ready.complete(Unit)
        runCurrent()
        val completed = assertNotNull(f.progress)
        assertEquals(completed.latestDemand, completed.completedDemand)
        assertNull(completed.pass)
        assertEquals(listOf<AppResult<Unit>>(AppResult.Success(Unit)), outcomes)
        job.cancelAndJoin()
    }

    @Test
    fun historyWriteContentionParksWithoutReportingCommandFailureOrStartingATimerRetry() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.destination.value = AppResult.Success(CollaborationSpaceInboxVersion(f.id(72), 0, 0))
        val busy =
            Failure(
                FailureKind.Conflict,
                "Conditional write changed",
                code = "client.collaboration_inbox_busy",
            )
        f.onHistoryTurn = { _, _ -> AppResult.Failed(busy) }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(outcomes) }
        runCurrent()
        assertEquals(listOf<AppResult<Unit>>(AppResult.Success(Unit)), outcomes)
        val attempts = f.historyCalls.size
        val progress = f.progress
        f.destination.value = AppResult.Success(CollaborationSpaceInboxVersion(f.id(72), 9, 9))
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(attempts, f.historyCalls.size)
        assertEquals(progress, f.progress)
        f.onHistoryTurn = { _, _ ->
            AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle)
        }
        f.historySignals.value = AppResult.Success(f.id(75))
        runCurrent()
        assertEquals(attempts + 1, f.historyCalls.size)
        assertEquals(listOf<AppResult<Unit>>(AppResult.Success(Unit)), outcomes)
        job.cancelAndJoin()
    }

    @Test
    fun unrelatedConflictsStorageAndAccessDenialAreStillReported() = runTest {
        for (failure in
            listOf(
                Failure(FailureKind.Conflict, "Other conflict", code = "other_conflict"),
                Failure(
                    FailureKind.Storage,
                    "Storage failed",
                    code = "client.collaboration_inbox_busy",
                ),
                Failure(FailureKind.AccessDenied, "Access denied"),
            )) {
            val f = SpaceInboxRepairWorkerFixture()
            f.onHistoryTurn = { _, _ -> AppResult.Failed(failure) }
            val outcomes = mutableListOf<AppResult<Unit>>()
            val job = backgroundScope.launch { f.worker()(f.access).toList(outcomes) }
            runCurrent()
            assertEquals(AppResult.Failed(failure), outcomes.last())
            job.cancelAndJoin()
        }
    }

    @Test
    fun unobservedHistoryMetadataDoesNotBlockCommandInitializationOrNewOriginals() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.observeHistory = { flow { awaitCancellation() } }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(f.progress?.latestDemand, assertNotNull(f.progress).completedDemand)
        f.records.value = AppResult.Success(listOf(f.queued))
        runCurrent()
        assertEquals(listOf(f.queued.intent), f.seeded)
        assertTrue(f.historyCalls.isEmpty())
        job.cancelAndJoin()
        assertEquals(mapOf("history" to 1, "catalog" to 1, "destination" to 1), f.historyStops)
    }

    @Test
    fun independentInitialMetadataEmissionsCannotOverwriteEachOther() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val catalog = CollaborationSpaceConversationCatalogVersion(f.id(71), 4)
        val destination = CollaborationSpaceInboxVersion(f.id(72), 2, 3)
        f.historySignals.value = AppResult.Success(f.id(70))
        f.catalog.value = AppResult.Success(catalog)
        f.destination.value = AppResult.Success(destination)
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        val frame = f.historyCalls.last().first
        assertEquals(f.id(70), frame.trigger.historySignal)
        assertEquals(catalog, frame.trigger.catalog)
        assertEquals(destination, frame.destination)
        assertEquals(destination.generation, frame.trigger.destinationGeneration)
        assertEquals(f.progress?.completedDemand, frame.trigger.completedRepairDemand)
        assertEquals(1, f.requestStarts)
        job.cancelAndJoin()
    }

    @Test
    fun finiteContinuationServicesANewCommandBeforeTheNextHistoryStep() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        var visited = 0
        f.onHistoryTurn = { _, _ ->
            visited++
            if (visited == 1) f.records.value = AppResult.Success(listOf(f.queued))
            if (visited == 2) assertEquals(listOf(f.queued.intent), f.seeded)
            if (visited <= 4) f.advancedHistory()
            else AppResult.Success(CollaborationSpaceInboxHistoryTurnWorker.Outcome.Idle)
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(5, visited)
        assertEquals(1, f.historyCalls.count { it.second })
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(5, visited)
        job.cancelAndJoin()
    }

    @Test
    fun failedHistoryStaysParkedAcrossPhysicalVersionsAndUnrelatedPendingMessages() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.destination.value = AppResult.Success(CollaborationSpaceInboxVersion(f.id(72), 0, 0))
        f.onHistoryTurn = { _, _ -> AppResult.Failed(f.storageFailure) }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        val attempts = f.historyCalls.size
        repeat(40) { n ->
            f.destination.value =
                AppResult.Success(CollaborationSpaceInboxVersion(f.id(72), n + 1L, n + 1L))
            f.records.value = AppResult.Success(listOf(f.queued.copy(revision = n + 1L)))
            runCurrent()
        }
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(attempts, f.historyCalls.size)
        assertEquals(1, f.requestStarts)
        job.cancelAndJoin()
    }

    @Test
    fun realHistoryCatalogCompletionAndDestinationReplacementEachWakeParkedHistory() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onHistoryTurn = { _, _ -> AppResult.Failed(f.storageFailure) }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        var count = f.historyCalls.size
        f.historySignals.value = AppResult.Success(f.id(70))
        runCurrent()
        assertEquals(++count, f.historyCalls.size)
        f.catalog.value =
            AppResult.Success(CollaborationSpaceConversationCatalogVersion(f.id(71), 0))
        runCurrent()
        assertEquals(++count, f.historyCalls.size)
        val signals =
            assertIs<AppResult.Success<CollaborationSpaceInboxRepairSignals>>(f.demand.value).value
        f.demand.value = AppResult.Success(signals.copy(completedDemand = f.id(73)))
        runCurrent()
        assertEquals(++count, f.historyCalls.size)
        f.destination.value = AppResult.Success(CollaborationSpaceInboxVersion(f.id(72), 0, 0))
        runCurrent()
        assertEquals(++count, f.historyCalls.size)
        assertEquals(1, f.historyCalls.count { it.second })
        job.cancelAndJoin()
    }

    @Test
    fun metadataFailureStopsHistoryButCommandsContinueAndRestorationRecoversOnce() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onHistoryTurn = { _, _ -> AppResult.Failed(f.storageFailure) }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        val attempts = f.historyCalls.size
        f.historySignals.value = AppResult.Failed(f.storageFailure)
        runCurrent()
        f.records.value = AppResult.Success(listOf(f.queued))
        runCurrent()
        assertEquals(listOf(f.queued.intent), f.seeded)
        assertEquals(attempts, f.historyCalls.size)
        f.historySignals.value = AppResult.Success(null)
        runCurrent()
        assertEquals(attempts + 1, f.historyCalls.size)
        assertTrue(f.historyCalls.last().second)
        f.records.value = AppResult.Success(listOf(f.queued.copy(revision = 2)))
        runCurrent()
        assertEquals(attempts + 1, f.historyCalls.size)
        job.cancelAndJoin()
    }

    @Test
    fun knownMetadataDenialCannotBeReopenedByUnrelatedInventoryOrOtherMetadata() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val denied = Failure(FailureKind.AccessDenied, "Denied")
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        f.catalog.value = AppResult.Failed(denied)
        runCurrent()
        val attempts = f.historyCalls.size
        f.historySignals.value = AppResult.Success(f.id(70))
        f.records.value = AppResult.Success(listOf(f.queued))
        runCurrent()
        assertEquals(attempts, f.historyCalls.size)
        f.catalog.value = AppResult.Success(null)
        runCurrent()
        assertEquals(attempts + 1, f.historyCalls.size)
        assertTrue(f.historyCalls.last().second)
        job.cancelAndJoin()
    }

    @Test
    fun aSuccessfulHistoryTurnNeverClearsAnUnresolvedCommandFailure() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onPage = { _, _ -> AppResult.Failed(f.storageFailure) }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(outcomes) }
        runCurrent()
        assertEquals(AppResult.Failed(f.storageFailure), outcomes.last())
        f.historySignals.value = AppResult.Success(f.id(70))
        runCurrent()
        assertEquals(AppResult.Failed(f.storageFailure), outcomes.last())
        assertEquals(1, f.pages.size)
        job.cancelAndJoin()
    }

    @Test
    fun aSuspendedHistoryTurnTimesOutOnceAndDoesNotRetryOnTheClock() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        var cancelled = 0
        f.onHistoryTurn = { _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(outcomes) }
        runCurrent()
        assertEquals(listOf<AppResult<Unit>>(AppResult.Success(Unit)), outcomes)
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, cancelled)
        assertIs<AppResult.Failed>(outcomes.last())
        val attempts = f.historyCalls.size
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(attempts, f.historyCalls.size)
        job.cancelAndJoin()
    }

    @Test
    fun cancellationJoinsAllMetadataAndDiscardsALateHistoryResult() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onHistoryTurn = { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            f.advancedHistory()
        }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(outcomes) }
        runCurrent()
        entered.await()
        job.cancel()
        runCurrent()
        assertFalse(job.isCompleted)
        release.complete(Unit)
        job.join()
        assertEquals(listOf<AppResult<Unit>>(AppResult.Success(Unit)), outcomes)
        assertEquals(1, f.historyCalls.size)
        assertEquals(f.historyStarts, f.historyStops)
        assertEquals(1, f.commandStops)
        assertEquals(1, f.requestStops)
    }

    @Test
    fun ownerRestartConsumesOneNewRecoveryAndNeverLeavesTheOldObserversRunning() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.onHistoryTurn = { _, _ -> AppResult.Failed(f.storageFailure) }
        val first = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        first.cancelAndJoin()
        assertEquals(f.historyStarts, f.historyStops)
        val attempts = f.historyCalls.size
        val second = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(attempts + 1, f.historyCalls.size)
        assertTrue(f.historyCalls.last().second)
        assertEquals(2, f.requestStarts)
        second.cancelAndJoin()
        assertEquals(f.historyStarts, f.historyStops)
        assertTrue(f.historyStops.values.all { it == 2 })
    }
}
