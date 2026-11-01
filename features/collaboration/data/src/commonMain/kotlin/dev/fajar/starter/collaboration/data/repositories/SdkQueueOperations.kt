package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One native operation, no backlog. A disappearing waiter cannot cancel a late store commit. */
internal class SdkQueueOperations(private val lifetime: CoroutineScope) {
    private val mutation = Mutex()
    private var active: Job? = null
    private var stopped = false

    suspend fun <T> execute(operation: suspend () -> AppResult<T>): AppResult<T> {
        currentCoroutineContext().ensureActive()
        val task =
            mutation.withLock {
                if (stopped || !lifetime.isActive)
                    return unavailable("client.collaboration_queue_closed")
                if (active?.isCompleted == false)
                    return unavailable("client.collaboration_queue_pending")
                lifetime.async { operation() }.also { active = it }
            }
        return try {
            withTimeoutOrNull(5_000) { task.await() }
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
