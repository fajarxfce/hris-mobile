package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native children must finish before the parent drains its database and releases its lock. */
internal class SdkChildResources(
    private val lifetime: CoroutineScope,
    private val capacity: Int = 8,
) {
    private val mutation = Mutex()
    private val children = mutableSetOf<SdkChildLease<*>>()
    private var stopped = false

    init {
        require(capacity in 1..8)
    }

    suspend fun <T> acquire(
        resource: T,
        initialize: suspend (T) -> AppResult<Unit>,
        release: suspend (T) -> Unit,
    ): AppResult<SdkChildLease<T>> {
        val child =
            mutation.withLock {
                if (stopped) return unavailable("client.collaboration_children_stopped")
                if (children.size >= capacity)
                    return unavailable("client.collaboration_children_full")
                SdkChildLease(lifetime, resource, initialize, release) { released ->
                        mutation.withLock { children.remove(released) }
                    }
                    .also { children.add(it) }
            }
        var handedOver = false
        try {
            val result = child.await()
            currentCoroutineContext().ensureActive()
            return mutation.withLock {
                if (stopped) unavailable("client.collaboration_children_stopped")
                else
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> AppResult.Success(child).also { handedOver = true }
                    }
            }
        } finally {
            // Cancellation or a timed-out waiter cannot orphan a late native acquisition.
            if (!handedOver) child.close()
        }
    }

    suspend fun close(): AppResult<Unit> {
        val current =
            mutation.withLock {
                stopped = true
                children.toList()
            }
        val results = coroutineScope { current.map { async { it.close() } }.awaitAll() }
        return results.filterIsInstance<AppResult.Failed>().firstOrNull() ?: AppResult.Success(Unit)
    }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat resources are unavailable.", code = code)
        )
}

/** Acquisition and disposal belong to the parent lifetime, independently of the screen waiter. */
internal class SdkChildLease<T>(
    lifetime: CoroutineScope,
    private val resource: T,
    initialize: suspend (T) -> AppResult<Unit>,
    release: suspend (T) -> Unit,
    released: suspend (SdkChildLease<T>) -> Unit,
) {
    private val acquisition =
        lifetime.async {
            when (val initialized = initialize(resource)) {
                is AppResult.Failed -> initialized
                is AppResult.Success -> AppResult.Success(resource)
            }
        }
    private val shutdown =
        SdkStoreShutdown(lifetime) {
            // Even failed initialization may already own a room, timeline, or native task.
            awaitAcquisition()
            val result = safeStorageCall { release(resource) }
            if (result is AppResult.Success) released(this)
            SdkShutdownAttempt(result, released = result is AppResult.Success)
        }

    suspend fun await(): AppResult<T> =
        withTimeoutOrNull(5_000) { awaitAcquisition() }
            ?: AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat is still opening.",
                    code = "client.collaboration_child_pending",
                )
            )

    private suspend fun awaitAcquisition(): AppResult<T> =
        try {
            acquisition.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!acquisition.isCancelled) throw cancelled
            AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Chat initialization was interrupted.",
                    code = "client.collaboration_child_interrupted",
                )
            )
        }

    suspend fun close(): AppResult<Unit> = shutdown.close()
}
