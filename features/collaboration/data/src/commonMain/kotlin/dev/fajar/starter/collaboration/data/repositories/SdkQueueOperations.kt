package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One native operation, no backlog. A disappearing waiter cannot cancel a late store commit. */
internal class SdkQueueOperations(
    private val lifetime: CoroutineScope,
    private val onInterrupted: () -> Unit = {},
) {
    private val mutation = Mutex()
    private var active: Job? = null
    private var stopped = false
    private val interrupted = MutableStateFlow(false)

    suspend fun <T> execute(
        waitMillis: Long = 5_000,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T> {
        require(waitMillis in 1..30_000)
        currentCoroutineContext().ensureActive()
        val task =
            mutation.withLock {
                if (interrupted.value) return unavailable("client.collaboration_queue_interrupted")
                if (stopped || !lifetime.isActive)
                    return unavailable("client.collaboration_queue_closed")
                if (active?.isCompleted == false)
                    return unavailable("client.collaboration_queue_pending")
                lifetime
                    .async { operation() }
                    .also { task ->
                        active = task
                        // Unlike waiter cancellation, cancellation of the native operation itself
                        // gives no quiescence guarantee for detached SDK storage work. Retire the
                        // runtime through its full pool drain, even when no waiter remains.
                        task.invokeOnCompletion { cause ->
                            if (
                                cause is CancellationException &&
                                    interrupted.compareAndSet(false, true)
                            )
                                onInterrupted()
                        }
                    }
            }
        return try {
            withTimeoutOrNull(waitMillis) { task.await() }
                ?: unavailable("client.collaboration_queue_pending")
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!task.isCancelled) throw cancelled
            unavailable("client.collaboration_queue_interrupted")
        }
    }

    /** Called by the parent-owned child shutdown, whose public waiter is already bounded. */
    suspend fun stop() {
        val pending =
            mutation.withLock {
                stopped = true
                active
            }
        pending?.join()
    }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat queue is unavailable.", code = code)
        )
}
