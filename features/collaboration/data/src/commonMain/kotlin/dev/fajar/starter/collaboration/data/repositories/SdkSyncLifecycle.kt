package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkSyncConnection
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One SDK lease owns acquisition even when its caller disappears. No automatic retry. */
internal class SdkSyncLifecycle(
    private val lifetime: CoroutineScope,
    private val acquire: suspend () -> CollaborationSdkSyncConnection,
) {
    private val mutation = Mutex()
    private val stopping = Mutex()
    private var stopped = false
    private var opening: Deferred<AppResult<CollaborationSdkSyncConnection>>? = null

    suspend fun start(): AppResult<Unit> {
        val pending =
            mutation.withLock {
                if (stopped) return unavailable("client.collaboration_sync_stopped")
                val existing = opening
                // Only a new explicit call retries a completed failed acquisition.
                if (
                    existing != null && existing.isCompleted && existing.await() is AppResult.Failed
                )
                    opening = null
                opening ?: lifetime.async { safeStorageCall { acquire() } }.also { opening = it }
            }
        val result =
            withTimeoutOrNull(5_000) { pending.await() }
                ?: return unavailable("client.collaboration_sync_pending")
        currentCoroutineContext().ensureActive()
        return mutation.withLock {
            if (stopped) unavailable("client.collaboration_sync_stopped")
            else
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> AppResult.Success(Unit)
                }
        }
    }

    /** Called by the lease-owned shutdown producer before native database draining. */
    suspend fun stop(): AppResult<Unit> =
        stopping.withLock {
            val pending =
                mutation.withLock {
                    stopped = true
                    opening
                } ?: return@withLock AppResult.Success(Unit)
            when (val acquired = pending.await()) {
                is AppResult.Failed -> AppResult.Success(Unit)
                is AppResult.Success -> safeStorageCall { acquired.value.stop() }
            }
        }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat synchronization is unavailable.", code = code)
        )
}
