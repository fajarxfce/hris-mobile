package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationPusherApi
import kotlinx.coroutines.*

/** Registered as a runtime child before I/O; closing it leaves the borrowed HTTP pool alive. */
internal class CollaborationPusherRequestSource(
    private val lifetime: CoroutineScope,
    private val api: CollaborationPusherApi,
) {
    private var request: Deferred<Result<Unit>>? = null

    fun open(accessToken: String) {
        check(request == null)
        request =
            lifetime.async {
                try {
                    Result.success(api.activate(accessToken))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
    }

    suspend fun await() = requireNotNull(request).await().getOrThrow()

    suspend fun stop() {
        request?.cancelAndJoin()
    }
}
