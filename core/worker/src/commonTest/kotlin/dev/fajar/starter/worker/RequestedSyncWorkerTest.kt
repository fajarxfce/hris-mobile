@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.worker

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class RequestedSyncWorkerTest {
    @Test
    fun startupAndTaskOutcomesDoNotCreateImplicitPolling() = runTest {
        var calls = 0
        var outcome: SyncResult = SyncResult.Complete
        val task =
            object : SyncTask {
                override val key = "recovery"

                override suspend fun invoke(): SyncResult {
                    calls++
                    return outcome
                }
            }
        val scheduler = ForegroundWorkScheduler(setOf(task.key))
        val worker = RequestedSyncWorker(listOf(task), scheduler).start(backgroundScope)
        runCurrent()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(0, calls)
        val failure = Failure(FailureKind.Network, "Unavailable")
        for (result in
            listOf(
                SyncResult.Complete,
                SyncResult.Retry(),
                SyncResult.Retry(failure),
                SyncResult.Blocked(failure),
            )) {
            outcome = result
            val before = calls
            scheduler.notify(task.key)
            runCurrent()
            assertEquals(before + 1, calls)
            advanceTimeBy(3_600_000)
            runCurrent()
            assertEquals(before + 1, calls)
        }
        worker.cancelAndJoin()
        scheduler.close()
    }

    @Test
    fun retainedAndInFlightSignalsAreConflatedWithoutConcurrentExecutions() = runTest {
        var calls = 0
        var active = 0
        var maximum = 0
        val release = CompletableDeferred<Unit>()
        val task =
            object : SyncTask {
                override val key = "recovery"

                override suspend fun invoke(): SyncResult {
                    calls++
                    active++
                    maximum = maxOf(maximum, active)
                    try {
                        if (calls == 1) release.await()
                        return SyncResult.Complete
                    } finally {
                        active--
                    }
                }
            }
        val scheduler = ForegroundWorkScheduler(setOf(task.key))
        repeat(100) { assertTrue(scheduler.notify(task.key)) }
        val worker = RequestedSyncWorker(listOf(task), scheduler).start(backgroundScope)
        runCurrent()
        assertEquals(1, calls)
        repeat(100) { assertTrue(scheduler.notify(task.key)) }
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, maximum)
        worker.cancelAndJoin()
        scheduler.close()
    }

    @Test
    fun aStoppedOwnerReleasesExecutionAndLeavesTheNextSignalForItsReplacement() = runTest {
        var calls = 0
        var cancelled = 0
        val task =
            object : SyncTask {
                override val key = "recovery"

                override suspend fun invoke(): SyncResult {
                    calls++
                    if (calls == 1) {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                    return SyncResult.Complete
                }
            }
        val scheduler = ForegroundWorkScheduler(setOf(task.key))
        val runner = RequestedSyncWorker(listOf(task), scheduler)
        scheduler.notify(task.key)
        val first = runner.start(backgroundScope)
        runCurrent()
        scheduler.notify(task.key)
        first.cancelAndJoin()
        assertEquals(1, cancelled)
        advanceTimeBy(120_000)
        assertEquals(1, calls)
        val next = runner.start(backgroundScope)
        runCurrent()
        assertEquals(2, calls)
        next.cancelAndJoin()
        scheduler.close()
    }

    @Test
    fun anOccupiedLaneDoesNotBlockAnotherRequestedTask() = runTest {
        var firstCalls = 0
        var secondCalls = 0
        val first =
            object : SyncTask {
                override val key = "first"

                override suspend fun invoke(): SyncResult {
                    firstCalls++
                    awaitCancellation()
                }
            }
        val second =
            object : SyncTask {
                override val key = "second"

                override suspend fun invoke(): SyncResult {
                    secondCalls++
                    return SyncResult.Complete
                }
            }
        val scheduler = ForegroundWorkScheduler(setOf(first.key, second.key))
        val worker = RequestedSyncWorker(listOf(first, second), scheduler).start(backgroundScope)
        scheduler.notify(first.key)
        runCurrent()
        scheduler.notify(second.key)
        runCurrent()
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
        assertFalse(scheduler.notify("unregistered"))
        worker.cancelAndJoin()
        scheduler.close()
    }
}
