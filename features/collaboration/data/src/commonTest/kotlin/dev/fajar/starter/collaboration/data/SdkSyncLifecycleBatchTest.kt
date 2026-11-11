package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkSyncConnection
import dev.fajar.starter.collaboration.data.dto.SdkSyncBatch
import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import dev.fajar.starter.collaboration.data.repositories.SdkSyncLifecycle
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkSyncLifecycleBatchTest {
    @Test
    fun observationBeforeStartDoesNotAcquireAndReceivesTheOwnedConnection() = runTest {
        var acquisitions = 0
        val connection = LifecycleBatchConnection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                connection
            }
        val received = mutableListOf<SdkSyncBatch>()
        val observer = backgroundScope.launch { owner.observeBatches().toList(received) }
        runCurrent()
        assertEquals(0, acquisitions)
        assertEquals(0, connection.starts)
        assertTrue(received.isEmpty())
        assertIs<AppResult.Success<Unit>>(owner.start())
        connection.batches.value = batch("one")
        runCurrent()
        assertEquals(listOf(batch("one")), received)
        assertEquals(1, acquisitions)
        assertEquals(1, connection.starts)
        owner.stop()
        runCurrent()
        assertTrue(observer.isCompleted)
    }

    @Test
    fun laterAndConcurrentObserversShareTheLatestProcessedResponse() = runTest {
        var acquisitions = 0
        val connection = LifecycleBatchConnection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                acquisitions++
                connection
            }
        owner.start()
        connection.batches.value = batch("two", "one")
        val first = async { owner.observeBatches().first() }
        val second = async { owner.observeBatches().first() }
        assertEquals(batch("two", "one"), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(1, acquisitions)
        assertEquals(1, connection.starts)
        assertEquals(0, connection.stops)
    }

    @Test
    fun cancelledObserverLeavesSyncOwnedAndItsReplacementSeesCurrentBatch() = runTest {
        val connection = LifecycleBatchConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        owner.start()
        val observer = backgroundScope.launch { owner.observeBatches().collect() }
        runCurrent()
        observer.cancelAndJoin()
        connection.batches.value = batch("after-cancel")
        assertEquals(batch("after-cancel"), owner.observeBatches().first())
        assertEquals(1, connection.starts)
        assertEquals(0, connection.stops)
    }

    @Test
    fun slowConsumerCanDetectConflationFromEachResponsesOwnPositions() = runTest {
        val connection = LifecycleBatchConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        owner.start()
        val received = mutableListOf<SdkSyncBatch>()
        backgroundScope.launch { owner.observeBatches().toList(received) }
        connection.batches.value = batch("one")
        runCurrent()
        connection.batches.value = batch("two", "one")
        connection.batches.value = batch("three", "two")
        runCurrent()
        assertEquals(listOf(batch("one"), batch("three", "two")), received)
    }

    @Test
    fun transientAndTerminalFailuresDoNotInventOrClearSuccessfulBatches() = runTest {
        val connection = LifecycleBatchConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        owner.start()
        connection.batches.value = batch("one")
        for (problem in
            listOf(
                SdkSyncProblem("M_LIMIT_EXCEEDED", 1_000),
                SdkSyncProblem("M_FORBIDDEN", terminal = true),
            )) {
            connection.problems.value = problem
            runCurrent()
            assertNotNull(owner.failures.value)
            assertEquals(batch("one"), owner.observeBatches().first())
        }
        assertEquals(1, connection.starts)
    }

    @Test
    fun failedAcquisitionIsNotRetriedByObservation() = runTest {
        var acquisitions = 0
        val connection = LifecycleBatchConnection()
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                if (++acquisitions == 1) error("Fictional acquisition failure")
                connection
            }
        assertIs<AppResult.Failed>(owner.start())
        val observer = async { owner.observeBatches().first() }
        runCurrent()
        assertFalse(observer.isCompleted)
        assertEquals(1, acquisitions)
        owner.start()
        connection.batches.value = batch("recovered")
        assertEquals(batch("recovered"), observer.await())
        assertEquals(2, acquisitions)
        assertEquals(1, connection.starts)
    }

    @Test
    fun emptyCloseCompletesExistingAndFutureObserversWithoutAcquisition() = runTest {
        val owner = SdkSyncLifecycle(backgroundScope) { error("Must never acquire") }
        val received = async { owner.observeBatches().toList() }
        runCurrent()
        assertFalse(received.isCompleted)
        owner.stop()
        assertTrue(received.await().isEmpty())
        assertTrue(owner.observeBatches().toList().isEmpty())
    }

    @Test
    fun pendingAcquisitionAfterStopCannotPublishEvenItsInitialBatch() = runTest {
        val release = CompletableDeferred<Unit>()
        val connection = LifecycleBatchConnection().apply { batches.value = batch("late") }
        val owner =
            SdkSyncLifecycle(backgroundScope) {
                release.await()
                connection
            }
        val received = async { owner.observeBatches().toList() }
        val starter = async { owner.start() }
        runCurrent()
        val stopping = async { owner.stop() }
        runCurrent()
        assertTrue(received.await().isEmpty())
        assertFalse(stopping.isCompleted)
        release.complete(Unit)
        assertIs<AppResult.Failed>(starter.await())
        assertIs<AppResult.Success<Unit>>(stopping.await())
        assertEquals(0, connection.starts)
        assertEquals(1, connection.stops)
        assertTrue(owner.observeBatches().toList().isEmpty())
    }

    @Test
    fun failedTerminationStillEndsObservationAndRetainsCleanupOwnership() = runTest {
        val connection = LifecycleBatchConnection()
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        owner.start()
        val received = async { owner.observeBatches().toList() }
        runCurrent()
        connection.onStop = {
            connection.batches.value = batch("late")
            error("Fictional shutdown failure")
        }
        assertIs<AppResult.Failed>(owner.stop())
        assertTrue(received.await().isEmpty())
        assertTrue(owner.observeBatches().toList().isEmpty())
        connection.onStop = {}
        assertIs<AppResult.Success<Unit>>(owner.stop())
        assertEquals(2, connection.stops)
    }

    @Test
    fun cancellationOfPendingTerminationDoesNotReopenBatchObservation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val connection =
            LifecycleBatchConnection().apply {
                onStop = {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        val owner = SdkSyncLifecycle(backgroundScope) { connection }
        owner.start()
        val observer = async { owner.observeBatches().toList() }
        runCurrent()
        val stopping = async { owner.stop() }
        entered.await()
        stopping.cancelAndJoin()
        connection.batches.value = batch("after-cancelled-stop")
        assertTrue(observer.await().isEmpty())
        assertTrue(owner.observeBatches().toList().isEmpty())
        connection.onStop = {}
        owner.stop()
        assertEquals(2, connection.stops)
    }
}

private fun batch(next: String, previous: String? = null) =
    SdkSyncBatch(previous, next, emptyList(), false)

private class LifecycleBatchConnection : CollaborationSdkSyncConnection {
    override val problems = MutableStateFlow<SdkSyncProblem?>(null)
    override val completedFirstSync = MutableStateFlow(false)
    override val batches = MutableStateFlow<SdkSyncBatch?>(null)
    var starts = 0
    var stops = 0
    var onStop: suspend () -> Unit = {}

    override suspend fun start(): SdkSyncProblem? {
        starts++
        return problems.value?.takeIf { it.terminal }
    }

    override suspend fun stop() {
        stops++
        onStop()
    }
}
