package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HrisProofCleanupTest {
    private val pending =
        Failure(FailureKind.Unavailable, "Pending", code = "client.collaboration_close_pending")

    @Test
    fun pendingDrainCanFinishWithoutRestartingOrSkippingTheRelease() = runTest {
        var calls = 0
        drainHrisProofRuntime {
            calls++
            if (calls < 3) AppResult.Failed(pending) else AppResult.Success(Unit)
        }
        assertEquals(3, calls)
    }

    @Test
    fun nonPendingFailureIsPreservedAndNeverRetried() = runTest {
        var calls = 0
        val failed = failure {
            drainHrisProofRuntime {
                calls++
                AppResult.Failed(pending.copy(code = "client.collaboration_sdk_unavailable"))
            }
        }
        assertTrue(failed is HrisProofFailure)
        assertEquals("HrisProof_Unavailable_SdkUnavailable", (failed as HrisProofFailure).category)
        assertEquals(1, calls)
    }

    @Test
    fun perpetuallyPendingDrainHasAFiniteAttemptBudget() = runTest {
        var calls = 0
        val failed = failure {
            drainHrisProofRuntime {
                calls++
                AppResult.Failed(pending)
            }
        }
        assertTrue(failed is IllegalStateException)
        assertEquals(6, calls)
    }

    @Test
    fun cleanupAttemptsEveryResourceWithoutReplacingThePrimaryFailure() = runTest {
        val primary = IllegalArgumentException("operation")
        val first = IllegalStateException("first close")
        val second = IllegalStateException("second close")
        val attempted = mutableListOf<Int>()
        closeHrisProofResources(
            primary,
            listOf(
                {
                    attempted += 1
                    throw first
                },
                {
                    attempted += 2
                    throw second
                },
                { attempted += 3 },
            ),
        )
        assertEquals(listOf(1, 2, 3), attempted)
        assertArrayEquals(arrayOf(first, second), primary.suppressed)
    }

    @Test
    fun failedCleanupStillFailsAnOtherwiseSuccessfulOperation() = runTest {
        val first = IllegalStateException("first close")
        val second = IllegalStateException("second close")
        val failed = failure {
            closeHrisProofResources(null, listOf({ throw first }, { throw second }))
        }
        // Debug coroutine stack recovery may copy a standard exception and retain its origin.
        assertEquals(first.javaClass, failed.javaClass)
        assertEquals(first.message, failed.message)
        val original = if (failed === first) failed else failed.cause
        assertSame(first, original)
        assertArrayEquals(arrayOf(second), requireNotNull(original).suppressed)
    }

    @Test
    fun cancellationCannotSkipTheOwnedCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        var closed = false
        val job = launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                closeHrisProofResources(
                    cancelled,
                    listOf({
                        assertTrue(currentCoroutineContext().isActive)
                        closed = true
                    }),
                )
                throw cancelled
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(closed)
    }

    private suspend fun failure(block: suspend () -> Unit): Exception {
        try {
            block()
        } catch (failure: Exception) {
            return failure
        }
        error("Expected failure")
    }
}
