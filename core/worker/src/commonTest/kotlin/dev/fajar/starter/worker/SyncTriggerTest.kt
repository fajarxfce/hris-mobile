@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.worker

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.sync.domain.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.*

class SyncTriggerTest {
    @Test
    fun onlyStartupAndCompletedCadenceCanDiscoverNewWork() = runTest {
        val triggers = mutableListOf<SyncTrigger>()
        val task =
            object : SyncTask {
                override val key = "fixture"

                override suspend fun invoke(): SyncResult = error("Host must identify its trigger")

                override suspend fun invoke(trigger: SyncTrigger): SyncResult {
                    triggers += trigger
                    return if (triggers.size == 1)
                        SyncResult.Retry(Failure(FailureKind.Network, "Offline"))
                    else SyncResult.Complete
                }
            }
        val requests = ForegroundWorkScheduler(setOf(task.key))
        val host =
            ForegroundSyncWorker(listOf(task), requests, 10.seconds, 1.seconds)
                .start(backgroundScope)
        runCurrent()
        assertEquals(listOf(SyncTrigger.Scheduled), triggers)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(SyncTrigger.Requested, triggers.last())
        assertEquals(2, triggers.size)
        requests.notify(task.key)
        runCurrent()
        assertEquals(SyncTrigger.Requested, triggers.last())
        assertEquals(3, triggers.size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SyncTrigger.Scheduled, triggers.last())
        assertEquals(4, triggers.size)
        host.cancelAndJoin()
        requests.close()
    }

    @Test
    fun retainedStartupRequestAndHealthyContinuationDoNotBecomeScheduledScans() = runTest {
        val triggers = mutableListOf<SyncTrigger>()
        val task =
            object : SyncTask {
                override val key = "fixture"

                override suspend fun invoke(): SyncResult = error("Missing trigger")

                override suspend fun invoke(trigger: SyncTrigger): SyncResult {
                    triggers += trigger
                    return if (triggers.size == 1) SyncResult.Retry() else SyncResult.Complete
                }
            }
        val requests = ForegroundWorkScheduler(setOf(task.key))
        requests.notify(task.key)
        val host = ForegroundSyncWorker(listOf(task), requests).start(backgroundScope)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(SyncTrigger.Requested, SyncTrigger.Requested), triggers)
        host.cancelAndJoin()
        requests.close()
    }

    @Test
    fun requestedRunnerNeverCreatesAScheduledScanAfterARetryResult() = runTest {
        val triggers = mutableListOf<SyncTrigger>()
        val task =
            object : SyncTask {
                override val key = "fixture"

                override suspend fun invoke(): SyncResult = error("Missing trigger")

                override suspend fun invoke(trigger: SyncTrigger): SyncResult {
                    triggers += trigger
                    return SyncResult.Retry()
                }
            }
        val requests = ForegroundWorkScheduler(setOf(task.key))
        val host = RequestedSyncWorker(listOf(task), requests).start(backgroundScope)
        runCurrent()
        assertTrue(triggers.isEmpty())
        requests.notify(task.key)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(SyncTrigger.Requested), triggers)
        host.cancelAndJoin()
        requests.close()
    }

    @Test
    fun existingTaskAndExceptionCallbackRemainCompatible() = runTest {
        val failure = IllegalStateException("Fixture failure")
        val task =
            object : SyncTask {
                override val key = "fixture"

                override suspend fun invoke(): SyncResult = throw failure
            }
        var captured: Exception? = null
        assertIs<SyncResult.Blocked>(runSyncTask(task, { captured = it }))
        assertSame(failure, captured)
        captured = null
        assertIs<SyncResult.Blocked>(runSyncTask(task, SyncTrigger.Scheduled) { captured = it })
        assertSame(failure, captured)
    }
}
