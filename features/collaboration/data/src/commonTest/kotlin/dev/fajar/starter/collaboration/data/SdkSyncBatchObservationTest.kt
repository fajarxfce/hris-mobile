package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.sdk.SdkSyncPump
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkSyncBatchObservationTest {
    @Test
    fun observationDoesNotStartTheTransportOrClaimAResponse() = runTest {
        var calls = 0
        val pump = pump {
            calls++
            SdkSyncOutcome.Received(batch(null, "next"))
        }
        assertNull(pump.batches.first())
        runCurrent()
        assertEquals(0, calls)
        assertFalse(pump.completedFirstSync.value)
        pump.stop()
    }

    @Test
    fun failurePreservesTheLastExactResponseWithoutPublishingAReplacement() = runTest {
        val first = batch("old", "next")
        var calls = 0
        val problem = SdkSyncProblem("M_LIMIT_EXCEEDED", 10_000)
        val pump = pump {
            if (++calls == 1) SdkSyncOutcome.Received(first) else SdkSyncOutcome.Failed(problem)
        }
        pump.start()
        runCurrent()
        assertEquals(first, pump.batches.value)
        assertTrue(pump.completedFirstSync.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(problem, pump.problems.value)
        assertEquals(first, pump.batches.value)
        pump.stop()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun stoppingDrainsButDoesNotPublishTheResponseThatArrivesAfterStop() = runTest {
        val response = CompletableDeferred<SdkSyncOutcome>()
        val pump = pump { response.await() }
        pump.start()
        runCurrent()
        val closing = async { pump.stop() }
        runCurrent()
        assertFalse(closing.isCompleted)
        response.complete(SdkSyncOutcome.Received(batch("before", "after")))
        closing.await()
        assertNull(pump.batches.value)
        assertFalse(pump.completedFirstSync.value)
    }

    @Test
    fun cancelledStopWaiterRetainsOwnershipAndCannotPublishLateData() = runTest {
        val response = CompletableDeferred<SdkSyncOutcome>()
        val first = batch(null, "one")
        var calls = 0
        val pump = pump { if (++calls == 1) SdkSyncOutcome.Received(first) else response.await() }
        pump.start()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, calls)
        val abandoned = async { pump.stop() }
        runCurrent()
        abandoned.cancelAndJoin()
        assertFalse(response.isCancelled)
        val closing = async { pump.stop() }
        runCurrent()
        assertFalse(closing.isCompleted)
        response.complete(SdkSyncOutcome.Received(batch("one", "two")))
        closing.await()
        assertEquals(first, pump.batches.value)
        assertEquals(2, calls)
    }

    @Test
    fun lateObserverGetsTheLatestResponsesOwnPositionsWithoutPretendingToReplay() = runTest {
        var calls = 0
        val pump = pump {
            calls++
            SdkSyncOutcome.Received(batch("before-$calls", "after-$calls"))
        }
        pump.start()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(4, calls)
        assertEquals(batch("before-4", "after-4"), pump.batches.first())
        pump.stop()
        assertEquals(4, calls)
    }

    @Test
    fun diagnosticsNeverExposePositionsRoomsOrEventIds() {
        val room =
            SdkSyncRoomChange(
                "!private:example",
                SdkSyncMembership.Joined,
                listOf("\$private"),
                true,
            )
        val value = SdkSyncBatch("private-before", "private-after", listOf(room), true)
        for (text in
            listOf(room.toString(), value.toString(), SdkSyncOutcome.Received(value).toString())) {
            assertFalse("private" in text)
            assertTrue("redacted" in text)
        }
    }

    private fun batch(previous: String?, next: String) =
        SdkSyncBatch(previous, next, emptyList(), false)

    private fun TestScope.pump(call: suspend () -> SdkSyncOutcome) =
        SdkSyncPump(backgroundScope, call, { testScheduler.currentTime }, { 0 })
}
