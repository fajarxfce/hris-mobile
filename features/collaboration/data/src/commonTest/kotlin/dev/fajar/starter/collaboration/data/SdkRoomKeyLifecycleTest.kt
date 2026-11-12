package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkRoomKeySource
import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import dev.fajar.starter.collaboration.data.repositories.SdkRoomKeyLifecycle
import dev.fajar.starter.collaboration.data.sdk.SdkRoomKeyMailbox
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkRoomKeyLifecycleTest {
    @Test
    fun readingAndObservingNeverOpenTheNativeSource() = runTest {
        val source = Source()
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val values = mutableListOf<SdkRoomKeyBatch>()
        val observer = backgroundScope.launch { owner.observe().toList(values) }
        runCurrent()
        assertNull(owner.current())
        assertFalse(owner.acknowledge("absent"))
        assertEquals(0, source.starts)
        owner.deactivate()
        runCurrent()
        assertTrue(observer.isCompleted)
        assertTrue(values.isEmpty())
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(0, source.starts)
    }

    @Test
    fun concurrentCallersShareOneOpeningAndOneSuccessfulRelease() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStart = { gate.await() }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val first = async { owner.start() }
        val second = async { owner.start() }
        runCurrent()
        assertEquals(1, source.starts)
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(AppResult.Success(Unit), owner.start())
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(1, source.starts)
        assertEquals(1, source.stops)
    }

    @Test
    fun cancellingAStarterDoesNotCancelSharedAcquisition() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStart = { gate.await() }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val caller = launch { owner.start() }
        runCurrent()
        caller.cancelAndJoin()
        assertFalse(source.acquired)
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), owner.start())
        assertTrue(source.acquired)
        assertEquals(1, source.starts)
        owner.stop()
    }

    @Test
    fun timedOutWaiterLeavesOneOwnedAcquisitionForALaterCaller() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStart = { gate.await() }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val caller = async { owner.start() }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertIs<AppResult.Failed>(caller.await())
        assertEquals(1, source.starts)
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), owner.start())
        assertEquals(1, source.starts)
        owner.stop()
    }

    @Test
    fun closingBeforeOpeningDoesNotAcquireNativeResources() = runTest {
        val source = Source()
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertIs<AppResult.Failed>(owner.start())
        assertEquals(0, source.starts)
        assertEquals(1, source.stops)
    }

    @Test
    fun unavailableSubscriptionDoesNotCreateAnAcquisitionLoop() = runTest {
        val source = Source().apply { available = false }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        repeat(5) { assertIs<AppResult.Failed>(owner.start()) }
        assertEquals(1, source.starts)
        owner.stop()
        assertEquals(1, source.stops)
    }

    @Test
    fun failedOpeningPreservesItsFailureWithoutAutomaticRetry() = runTest {
        val source = Source().apply { beforeStart = { error("Fixture acquisition failure") } }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val failed = assertIs<AppResult.Failed>(owner.start())
        assertEquals(failed, owner.start())
        assertEquals(1, source.starts)
        owner.stop()
        assertEquals(1, source.stops)
    }

    @Test
    fun shutdownWaitsForALateAcquisitionBeforeReleasingTheSource() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStart = { withContext(NonCancellable) { gate.await() } }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val caller = async { owner.start() }
        runCurrent()
        val closing = async { owner.stop() }
        runCurrent()
        assertTrue(source.deactivated)
        assertFalse(closing.isCompleted)
        assertEquals(0, source.stops)
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), closing.await())
        assertIs<AppResult.Failed>(caller.await())
        assertTrue(source.acquired)
        assertEquals(1, source.stops)
    }

    @Test
    fun failedStopKeepsTheOwnedSourceForAnExplicitCleanupRetry() = runTest {
        val source = Source().apply { beforeStop = { error("Fixture drain failure") } }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        owner.start()
        assertIs<AppResult.Failed>(owner.stop())
        assertIs<AppResult.Failed>(owner.start())
        assertEquals(1, source.starts)
        source.beforeStop = {}
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(2, source.stops)
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(2, source.stops)
    }

    @Test
    fun cancellingCleanupDuringLateOpeningRetainsTheSourceForItsOwner() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStart = { withContext(NonCancellable) { gate.await() } }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        val caller = launch { owner.start() }
        runCurrent()
        val closing = launch { owner.stop() }
        runCurrent()
        closing.cancelAndJoin()
        assertEquals(0, source.stops)
        gate.complete(Unit)
        caller.join()
        assertEquals(AppResult.Success(Unit), owner.stop())
        assertEquals(1, source.stops)
    }

    @Test
    fun concurrentStopsWaitForTheSameRelease() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStop = { gate.await() }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        owner.start()
        val first = async { owner.stop() }
        val second = async { owner.stop() }
        runCurrent()
        assertEquals(1, source.stops)
        assertFalse(second.isCompleted)
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), first.await())
        assertEquals(AppResult.Success(Unit), second.await())
        assertEquals(1, source.stops)
    }

    @Test
    fun cancellingAnObserverDoesNotAcknowledgeItsBatchOrStopTheSource() = runTest {
        val source = Source()
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        owner.start()
        source.mailbox.offer(listOf("!room:test"), false)
        val first = owner.observe().first()
        assertEquals(first, owner.current())
        assertEquals(0, source.stops)
        assertTrue(owner.acknowledge(first.receiptId))
        assertNull(owner.current())
        owner.stop()
    }

    @Test
    fun retirementFencesBufferedAndLateDataBeforeTheDrainCompletes() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.beforeStop = { gate.await() }
        val owner = SdkRoomKeyLifecycle(backgroundScope, source)
        owner.start()
        source.mailbox.offer(listOf("!room:test"), false)
        val first = assertNotNull(owner.current())
        owner.deactivate()
        val closing = async { owner.stop() }
        runCurrent()
        source.mailbox.offer(listOf("!late:test"), false)
        assertNull(owner.current())
        assertFalse(owner.acknowledge(first.receiptId))
        assertTrue(owner.observe().toList().isEmpty())
        assertFalse(closing.isCompleted)
        gate.complete(Unit)
        assertEquals(AppResult.Success(Unit), closing.await())
    }

    private class Source : CollaborationSdkRoomKeySource {
        val mailbox = SdkRoomKeyMailbox()
        var starts = 0
        var stops = 0
        var acquired = false
        var available = true
        var deactivated = false
        var beforeStart: suspend () -> Unit = {}
        var beforeStop: suspend () -> Unit = {}

        override suspend fun start(): Boolean {
            starts++
            withContext(NonCancellable) {
                beforeStart()
                acquired = true
            }
            return available
        }

        override fun current() = mailbox.current()

        override fun observe() = mailbox.observe()

        override fun acknowledge(receiptId: String) = mailbox.acknowledge(receiptId)

        override fun deactivate() {
            deactivated = true
        }

        override suspend fun stop() {
            stops++
            beforeStop()
            mailbox.close()
        }
    }
}
