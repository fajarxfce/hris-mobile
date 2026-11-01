package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import dev.fajar.starter.collaboration.data.sdk.SdkSyncPump
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkSyncPumpTest {
    @Test
    fun denialReceivedWhileStoppingIsRetainedWithoutStartingAnotherRequest() = runTest {
        val response = CompletableDeferred<Unit>()
        val denied = SdkSyncProblem("M_FORBIDDEN", terminal = true)
        var calls = 0
        val pump = pump {
            calls++
            response.await()
            denied
        }
        pump.start()
        runCurrent()
        val closing = async { pump.stop() }
        runCurrent()
        response.complete(Unit)
        closing.await()
        assertEquals(denied, pump.problems.value)
        assertEquals(denied, pump.start())
        assertEquals(1, calls)
    }

    @Test
    fun busyResponseRetainsOneProducerAndHonorsServerDelay() = runTest {
        var calls = 0
        val arrived = CompletableDeferred<Unit>()
        val pump = pump {
            if (++calls == 1) SdkSyncProblem("M_LIMIT_EXCEEDED", 2_500)
            else {
                arrived.complete(Unit)
                awaitCancellation()
            }
        }
        assertNull(pump.start())
        runCurrent()
        repeat(10) { assertNull(pump.start()) }
        advanceTimeBy(2_499)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        arrived.await()
        assertEquals(2, calls)
    }

    @Test
    fun interruptionHasFiniteRetriesAndExplicitRestartKeepsSameConnection() = runTest {
        var calls = 0
        val pump = pump {
            calls++
            SdkSyncProblem(null)
        }
        pump.start()
        runCurrent()
        for (wait in listOf(1_000L, 2_000, 4_000, 8_000, 16_000)) {
            advanceTimeBy(wait)
            runCurrent()
        }
        assertEquals(6, calls)
        assertNotNull(pump.start())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(6, calls)
        assertNull(pump.start())
        runCurrent()
        assertEquals(7, calls)
        pump.stop()
    }

    @Test
    fun successfulSyncClearsProblemAndRestartsBackoffBudget() = runTest {
        val times = mutableListOf<Long>()
        val pump = pump {
            times += testScheduler.currentTime
            when (times.size) {
                3 -> null
                5 -> awaitCancellation()
                else -> SdkSyncProblem(null)
            }
        }
        pump.start()
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertNull(pump.problems.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(0L, 1_000, 3_000, 4_000, 5_000), times)
    }

    @Test
    fun longServerHintPausesAndExplicitStartCannotShortenIt() = runTest {
        var calls = 0
        val problem = SdkSyncProblem("M_LIMIT_EXCEEDED", 90_000)
        val pump = pump {
            calls++
            problem
        }
        pump.start()
        runCurrent()
        repeat(5) { assertEquals(problem, pump.start()) }
        advanceTimeBy(89_999)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(problem, pump.start())
        advanceTimeBy(1)
        assertNull(pump.start())
        runCurrent()
        assertEquals(2, calls)
        pump.stop()
    }

    @Test
    fun terminalDenialNeverRetriesOrReopensOnSameHandle() = runTest {
        for (code in listOf("M_UNKNOWN_TOKEN", "M_FORBIDDEN", "M_INVALID_PARAM")) {
            var calls = 0
            val problem = SdkSyncProblem(code, terminal = true)
            val pump = pump {
                calls++
                problem
            }
            pump.start()
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(problem, pump.start())
            assertEquals(1, calls)
            pump.stop()
            assertEquals(problem, pump.start())
        }
    }

    @Test
    fun stoppingWakesBackoffAndPreventsAnotherRequest() = runTest {
        var calls = 0
        val pump = pump {
            calls++
            SdkSyncProblem(null)
        }
        pump.start()
        runCurrent()
        pump.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, calls)
        assertNotNull(pump.start())
    }

    @Test
    fun cancelledStopWaiterCannotCancelPendingSdkCallOrReleaseOwnership() = runTest {
        var calls = 0
        var finished = false
        val response = CompletableDeferred<Unit>()
        val pump = pump {
            calls++
            try {
                response.await()
                null
            } finally {
                finished = true
            }
        }
        pump.start()
        runCurrent()
        val first = async { pump.stop() }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(finished)
        val second = async { pump.stop() }
        runCurrent()
        assertFalse(second.isCompleted)
        assertNotNull(pump.start())
        response.complete(Unit)
        second.await()
        assertTrue(finished)
        assertEquals(1, calls)
        assertNull(pump.problems.value)
    }

    @Test
    fun fastSuccessfulResponsesAreThrottledAndOnlyOneCallIsInFlight() = runTest {
        var calls = 0
        val pump = pump {
            calls++
            null
        }
        pump.start()
        runCurrent()
        repeat(20) { pump.start() }
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        pump.stop()
    }

    private fun TestScope.pump(call: suspend () -> SdkSyncProblem?) =
        SdkSyncPump(backgroundScope, call, { testScheduler.currentTime }, { 0 })
}
