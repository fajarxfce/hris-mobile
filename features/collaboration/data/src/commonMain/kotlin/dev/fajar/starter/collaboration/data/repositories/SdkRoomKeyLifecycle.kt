package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSdkRoomKeySource
import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import dev.fajar.starter.common.result.*
import dev.fajar.starter.storage.safeStorageCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lease owns this permanent registration, independently of callers and transient rooms. */
internal class SdkRoomKeyLifecycle(
    lifetime: CoroutineScope,
    private val source: CollaborationSdkRoomKeySource,
) {
    private val stopped = MutableStateFlow(false)
    private val stopping = Mutex()
    private var released = false
    private val opening =
        lifetime.async(start = CoroutineStart.LAZY) { safeStorageCall { source.start() } }

    /** A cancelled or timed-out caller cannot cancel the shared native acquisition. */
    suspend fun start(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (stopped.value) return unavailable()
        val result =
            try {
                withTimeoutOrNull(5_000) { opening.await() } ?: return unavailable()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                return unavailable()
            }
        currentCoroutineContext().ensureActive()
        if (stopped.value) return unavailable()
        return when (result) {
            is AppResult.Failed -> result
            is AppResult.Success -> if (result.value) AppResult.Success(Unit) else unavailable()
        }
    }

    fun current(): SdkRoomKeyBatch? = if (stopped.value) null else source.current()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<SdkRoomKeyBatch> =
        source
            .observe()
            .map<SdkRoomKeyBatch, SdkRoomKeyBatch?> { it }
            .onStart { emit(null) }
            .combine(stopped) { batch, closed -> batch to closed }
            .transformWhile { (batch, closed) ->
                if (closed || stopped.value) false
                else {
                    if (batch != null) emit(batch)
                    true
                }
            }

    fun acknowledge(receiptId: String): Boolean = !stopped.value && source.acknowledge(receiptId)

    /** Registry retirement fences callbacks synchronously, before asynchronous draining. */
    fun deactivate() {
        stopped.value = true
        source.deactivate()
    }

    /** Failure retains ownership. The parent must not release its client or store yet. */
    suspend fun stop(): AppResult<Unit> {
        deactivate()
        return stopping.withLock {
            if (released) return@withLock AppResult.Success(Unit)
            opening.cancelAndJoin()
            safeStorageCall { source.stop() }.also { if (it is AppResult.Success) released = true }
        }
    }

    private fun unavailable() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Key observation is unavailable.",
                code = "client.collaboration_keys_unavailable",
            )
        )
}
