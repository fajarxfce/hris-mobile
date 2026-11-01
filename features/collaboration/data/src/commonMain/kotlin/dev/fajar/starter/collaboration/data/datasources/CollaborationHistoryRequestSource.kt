package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.api.CollaborationHistoryApi
import dev.fajar.starter.collaboration.data.dto.CollaborationHistoryRequest
import kotlinx.coroutines.*

/**
 * Registered before starting I/O; disposal cancels this request, never the borrowed HTTP client.
 */
internal class CollaborationHistoryRequestSource(
    private val lifetime: CoroutineScope,
    private val api: CollaborationHistoryApi,
    private val query: CollaborationHistoryRequest,
) {
    private var request: Deferred<Result<String>>? = null

    fun open(accessToken: String) {
        check(request == null)
        request =
            lifetime.async {
                try {
                    Result.success(api.read(accessToken, query))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Result.failure(failure)
                }
            }
    }

    suspend fun read(): String = requireNotNull(request).await().getOrThrow()

    suspend fun stop() {
        request?.cancelAndJoin()
    }
}
