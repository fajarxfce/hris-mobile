package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SdkQueueOperationsTest {
    @Test
    fun cancelledWaiterLeavesNativeCommitOwnedAndRejectsACompetingSnapshot() = runTest {
        val operations = SdkQueueOperations(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var commits = 0
        val waiter = launch {
            operations.execute {
                finish.await()
                commits++
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals(
            "client.collaboration_queue_pending",
            assertIs<AppResult.Failed>(
                    operations.execute<Unit> { error("No competing native operation") }
                )
                .failure
                .code,
        )
        finish.complete(Unit)
        runCurrent()
        assertEquals(AppResult.Success(1), operations.execute { AppResult.Success(commits) })
        operations.stop()
    }

    @Test
    fun timeoutDoesNotCancelNativeEnqueueOrStartAnAutomaticRetry() = runTest {
        val operations = SdkQueueOperations(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val waiter = async {
            operations.execute {
                calls++
                finish.await()
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(
            "client.collaboration_queue_pending",
            assertIs<AppResult.Failed>(waiter.await()).failure.code,
        )
        assertEquals(1, calls)
        val stopped = async { operations.stop() }
        runCurrent()
        assertFalse(stopped.isCompleted)
        assertEquals(
            "client.collaboration_queue_closed",
            assertIs<AppResult.Failed>(
                    operations.execute<Unit> { error("No enqueue during close") }
                )
                .failure
                .code,
        )
        finish.complete(Unit)
        stopped.await()
        assertEquals(1, calls)
    }

    @Test
    fun parentChildKeepsExclusiveResourceUntilLateNativeWorkDrains() = runTest {
        val resources = SdkChildResources(backgroundScope, 1)
        val operations = SdkQueueOperations(backgroundScope)
        var released = false
        val child =
            assertIs<AppResult.Success<SdkChildLease<Unit>>>(
                    resources.acquire(
                        Unit,
                        { AppResult.Success(Unit) },
                        {
                            operations.stop()
                            released = true
                        },
                    )
                )
                .value
        val finish = CompletableDeferred<Unit>()
        val writer = launch {
            operations.execute {
                finish.await()
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        writer.cancelAndJoin()
        assertIs<AppResult.Failed>(child.close())
        assertFalse(released)
        assertIs<AppResult.Failed>(resources.acquire(Unit, { error("Still owned") }, {}))
        finish.complete(Unit)
        runCurrent()
        assertTrue(released)
        assertEquals(AppResult.Success(Unit), child.close())
        assertEquals(AppResult.Success(Unit), resources.close())
    }

    @Test
    fun cancelledCloseWaiterCannotReleaseResourceBeforeNativeCommit() = runTest {
        val operations = SdkQueueOperations(backgroundScope)
        val finish = CompletableDeferred<Unit>()
        var released = false
        val shutdown =
            SdkStoreShutdown(backgroundScope) {
                operations.stop()
                released = true
                SdkShutdownAttempt(AppResult.Success(Unit), true)
            }
        val writer = launch {
            operations.execute {
                finish.await()
                AppResult.Success(Unit)
            }
        }
        runCurrent()
        writer.cancelAndJoin()
        val closer = launch { shutdown.close() }
        runCurrent()
        closer.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(released)
        finish.complete(Unit)
        runCurrent()
        assertTrue(released)
        assertEquals(AppResult.Success(Unit), shutdown.close())
    }

    @Test
    fun failureAndNativeCancellationRemainExplicitAndDoNotResetTheQueue() = runTest {
        val operations = SdkQueueOperations(backgroundScope)
        val denied =
            AppResult.Failed(
                Failure(FailureKind.AccessDenied, "Denied", code = "permission_denied")
            )
        assertEquals(denied, operations.execute { denied })
        assertEquals(
            "client.collaboration_queue_interrupted",
            assertIs<AppResult.Failed>(
                    operations.execute<Unit> {
                        throw CancellationException("Native operation interrupted")
                    }
                )
                .failure
                .code,
        )
        assertEquals(
            AppResult.Success("original-transaction"),
            operations.execute { AppResult.Success("original-transaction") },
        )
        operations.stop()
    }
}
