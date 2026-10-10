@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.worker

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ForegroundSyncWorkerTest {
    @Test
    fun successfulBatchesContinueWithoutNetworkBackoff() = runTest {
        var calls = 0
        val task =
            object : SyncTask {
                override val key = "batches"

                override suspend fun invoke(): SyncResult {
                    calls++
                    return if (calls < 3) SyncResult.Retry() else SyncResult.Complete
                }
            }
        val queue = ForegroundWorkScheduler(setOf(task.key))
        val worker = ForegroundSyncWorker(listOf(task), queue).start(backgroundScope)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(3, calls)
        worker.cancelAndJoin()
        queue.close()
    }

    @Test
    fun retriesBackOffAndARequestDuringExecutionIsRetained() = runTest {
        val firstUpload = CompletableDeferred<Unit>()
        var calls = 0
        val task =
            object : SyncTask {
                override val key = "test"

                override suspend fun invoke(): SyncResult {
                    calls++
                    if (calls == 1) firstUpload.await()
                    return if (calls < 4) SyncResult.Retry(Failure(FailureKind.Network, "Offline"))
                    else SyncResult.Complete
                }
            }
        val queue = ForegroundWorkScheduler(setOf(task.key))
        val worker =
            ForegroundSyncWorker(listOf(task), queue, interval = 60.seconds, retryDelay = 1.seconds)
                .start(backgroundScope)
        runCurrent()
        queue.enqueue(task.key)
        firstUpload.complete(Unit)
        runCurrent()
        assertEquals(2, calls) // explicit request was not lost while the first run was pending
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, calls)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(4, calls)
        worker.cancelAndJoin()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(4, calls)
        queue.close()
    }

    @Test
    fun blockedTaskWaitsForAnExplicitWakeup() = runTest {
        var calls = 0
        val task =
            object : SyncTask {
                override val key = "blocked"

                override suspend fun invoke(): SyncResult {
                    calls++
                    return SyncResult.Blocked(Failure(FailureKind.Validation, "Rejected"))
                }
            }
        val queue = ForegroundWorkScheduler(setOf(task.key))
        val worker =
            ForegroundSyncWorker(listOf(task), queue, interval = 60.seconds).start(backgroundScope)
        runCurrent()
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, calls)
        queue.enqueue(task.key)
        runCurrent()
        assertEquals(2, calls)
        worker.cancelAndJoin()
        queue.close()
    }

    @Test
    fun separateTaskQueuesAndOwnerCancellation() = runTest {
        val waiting = CompletableDeferred<Unit>()
        var cancelled = false
        var secondCalls = 0
        val first =
            object : SyncTask {
                override val key = "first"

                override suspend fun invoke(): SyncResult {
                    try {
                        waiting.await()
                    } finally {
                        cancelled = true
                    }
                    return SyncResult.Complete
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
        val queue = ForegroundWorkScheduler(setOf(first.key, second.key))
        val worker = ForegroundSyncWorker(listOf(first, second), queue).start(backgroundScope)
        runCurrent()
        queue.enqueue(second.key)
        runCurrent()
        assertEquals(2, secondCalls)
        worker.cancelAndJoin()
        assertTrue(cancelled)
        queue.close()
    }

    @Test
    fun unexpectedExceptionsRetainDiagnosticsAndCancellationPropagates() = runTest {
        val failure = IllegalStateException("Internal detail")
        var captured: Exception? = null
        val task =
            object : SyncTask {
                override val key = "broken"

                override suspend fun invoke(): SyncResult = throw failure
            }
        val result = assertIs<SyncResult.Blocked>(runSyncTask(task) { captured = it })
        assertSame(failure, captured)
        assertFalse(result.failure.message.contains("Internal detail"))
        val cancelled =
            object : SyncTask {
                override val key = "cancelled"

                override suspend fun invoke(): SyncResult = throw CancellationException()
            }
        assertFailsWith<CancellationException> { runSyncTask(cancelled) }
    }
}
