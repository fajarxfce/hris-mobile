package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.common.result.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A failed drain retains ownership. A completed release must never run twice. */
internal data class SdkShutdownAttempt(val result: AppResult<Unit>, val released: Boolean)

/** Per-lease close coordinator; caller cancellation cannot cancel native store draining. */
internal class SdkStoreShutdown(
    private val lifetime: CoroutineScope,
    private val release: suspend () -> SdkShutdownAttempt,
) {
    private val closing = Mutex()
    private var task: Deferred<SdkShutdownAttempt>? = null

    suspend fun close(): AppResult<Unit> =
        withContext(NonCancellable) {
            val current =
                closing.withLock { task ?: lifetime.async { release() }.also { task = it } }
            val attempt =
                withTimeoutOrNull(5_000) { current.await() }
                    ?: return@withContext AppResult.Failed(
                        Failure(
                            FailureKind.Unavailable,
                            "Chat storage is still closing.",
                            code = "client.collaboration_close_pending",
                        )
                    )
            if (!attempt.released) closing.withLock { if (task === current) task = null }
            attempt.result
        }
}
