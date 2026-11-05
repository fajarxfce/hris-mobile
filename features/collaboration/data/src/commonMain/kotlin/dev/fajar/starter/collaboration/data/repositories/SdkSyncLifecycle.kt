package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkSyncConnection
import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import dev.fajar.starter.collaboration.data.mappers.collaborationSdkFailure
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One SDK lease owns acquisition even when its caller disappears. No automatic acquisition retry.
 */
internal class SdkSyncLifecycle(
    private val lifetime: CoroutineScope,
    private val acquire: suspend () -> CollaborationSdkSyncConnection,
) {
    private val mutation = Mutex()
    private val stopping = Mutex()
    private val stopped = MutableStateFlow(false)
    private var observation: Job? = null
    private val mutableFailures = MutableStateFlow<Failure?>(null)
    val failures = mutableFailures.asStateFlow()
    private var opening: Deferred<AppResult<CollaborationSdkSyncConnection>>? = null

    suspend fun start(): AppResult<Unit> {
        val pending =
            mutation.withLock {
                if (stopped.value) return unavailable("client.collaboration_sync_stopped")
                val existing = opening
                // Only a new explicit call retries a completed failed acquisition.
                if (
                    existing != null && existing.isCompleted && existing.await() is AppResult.Failed
                )
                    opening = null
                opening
                    ?: lifetime
                        .async {
                            val result = safeStorageCall { acquire() }
                            if (result is AppResult.Success)
                                observation =
                                    lifetime.launch {
                                        result.value.problems.collect {
                                            mutableFailures.value = it?.toFailure()
                                        }
                                    }
                            result
                        }
                        .also { opening = it }
            }
        val result =
            withTimeoutOrNull(5_000) { pending.await() }
                ?: return unavailable("client.collaboration_sync_pending")
        currentCoroutineContext().ensureActive()
        return mutation.withLock {
            if (stopped.value) unavailable("client.collaboration_sync_stopped")
            else
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        when (val started = safeStorageCall { result.value.start() }) {
                            is AppResult.Failed -> started
                            is AppResult.Success ->
                                started.value?.let { AppResult.Failed(it.toFailure()) }
                                    ?: AppResult.Success(Unit)
                        }
                }
        }
    }

    /** Bounded observation of the owned loop; caller cancellation never abandons its producer. */
    suspend fun awaitFirstSync(): AppResult<Unit> {
        val started = start()
        if (started is AppResult.Failed) return started
        val acquired =
            mutation.withLock {
                if (stopped.value) return unavailable("client.collaboration_sync_stopped")
                requireNotNull(opening).await()
            }
        if (acquired is AppResult.Failed) return acquired
        val connection = (acquired as AppResult.Success).value
        val observed =
            withTimeoutOrNull(10_000) {
                combine(connection.completedFirstSync, connection.problems, stopped) {
                        synced,
                        problem,
                        closed ->
                        synced || problem?.terminal == true || closed
                    }
                    .first { it }
            }
        currentCoroutineContext().ensureActive()
        return mutation.withLock {
            connection.problems.value
                ?.takeIf { it.terminal }
                ?.let {
                    return@withLock AppResult.Failed(it.toFailure())
                }
            when {
                stopped.value -> unavailable("client.collaboration_sync_stopped")
                observed == true && connection.completedFirstSync.value -> AppResult.Success(Unit)
                else -> unavailable("client.collaboration_sync_pending")
            }
        }
    }

    /** Called by the lease-owned shutdown producer before native database draining. */
    suspend fun stop(): AppResult<Unit> =
        stopping.withLock {
            val pending =
                mutation.withLock {
                    stopped.value = true
                    opening
                } ?: return@withLock AppResult.Success(Unit)
            val result =
                when (val acquired = pending.await()) {
                    is AppResult.Failed -> AppResult.Success(Unit)
                    is AppResult.Success -> {
                        val stopped = safeStorageCall { acquired.value.stop() }
                        // The collector may not run between native completion and its disposal.
                        // Preserve an authoritative response received by that last pending call.
                        acquired.value.problems.value?.toFailure()?.let {
                            if (
                                it.kind in setOf(FailureKind.Unauthorized, FailureKind.AccessDenied)
                            )
                                mutableFailures.value = it
                        }
                        stopped
                    }
                }
            if (result is AppResult.Success) observation?.cancelAndJoin()
            result
        }

    private fun unavailable(code: String) =
        AppResult.Failed(
            Failure(FailureKind.Unavailable, "Chat synchronization is unavailable.", code = code)
        )
}

private fun SdkSyncProblem.toFailure(): Failure =
    when (matrixCode) {
        null,
        "CLIENT_SYNC_STOPPED" ->
            Failure(
                FailureKind.Unavailable,
                "Chat synchronization is interrupted.",
                code = "client.collaboration_sync_interrupted",
            )
        else -> collaborationSdkFailure(matrixCode, retryAfterMillis?.coerceAtLeast(0)?.toULong())
    }
