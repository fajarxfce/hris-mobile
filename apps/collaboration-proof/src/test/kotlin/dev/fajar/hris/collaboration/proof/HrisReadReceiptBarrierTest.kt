package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwnReadReceipts
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HrisReadReceiptBarrierTest {
    @Test
    fun peerBarrierDoesNotCompleteUntilBothOwnReceiptValuesArrive() = runTest {
        val target = "fictional-event"
        for (first in
            listOf(
                CollaborationOwnReadReceipts(null, target),
                CollaborationOwnReadReceipts(target, null),
            )) {
            var snapshot = first
            var reads = 0
            val ready = async {
                HrisReadReceiptProof.awaitOwnReceipts(target) {
                    reads++
                    AppResult.Success(snapshot)
                }
            }
            runCurrent()
            assertFalse(ready.isCompleted)
            advanceTimeBy(300)
            runCurrent()
            assertFalse(ready.isCompleted)
            snapshot = CollaborationOwnReadReceipts(target, target)
            advanceUntilIdle()
            ready.await()
            assertTrue(reads > 1)
        }
    }

    @Test
    fun differentEventNeverPassesAndDeadlineJoinsThePendingRead() = runTest {
        var reads = 0
        var joined = false
        val result = async {
            try {
                HrisReadReceiptProof.awaitOwnReceipts("expected") {
                    reads++
                    if (reads == 1)
                        AppResult.Success(CollaborationOwnReadReceipts("older", "older"))
                    else
                        try {
                            awaitCancellation()
                        } finally {
                            joined = true
                        }
                }
                false
            } catch (_: TimeoutCancellationException) {
                true
            }
        }
        advanceUntilIdle()
        assertTrue(result.await())
        assertTrue(joined)
        assertEquals(2, reads)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test
    fun ownerCancellationJoinsTheReadWithoutAReplacementAttempt() = runTest {
        var reads = 0
        var joined = false
        val job = launch {
            HrisReadReceiptProof.awaitOwnReceipts("expected") {
                reads++
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
        }
        runCurrent()
        job.cancelAndJoin()
        advanceUntilIdle()
        assertTrue(joined)
        assertEquals(1, reads)
    }

    @Test
    fun storageFailureIsNotPolledAwayAsSyncLag() = runTest {
        var reads = 0
        var failed = false
        try {
            HrisReadReceiptProof.awaitOwnReceipts("expected") {
                reads++
                AppResult.Failed(Failure(FailureKind.Storage, "Controlled unavailable store"))
            }
        } catch (_: HrisProofFailure) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(1, reads)
        assertEquals(0L, testScheduler.currentTime)
    }
}
