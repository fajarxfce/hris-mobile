package dev.fajar.starter.app.inspection

import dev.fajar.inspector.NetworkInspection
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.usecases.ObservePublishedSessionIdentity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/** The application container owns diagnostics independently of screens and message delivery. */
class ApplicationNetworkInspection(
    val inspection: NetworkInspection,
    observeIdentity: ObservePublishedSessionIdentity,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val closing = MutableStateFlow(false)
    private val terminated = CompletableDeferred<Unit>()

    init {
        scope.launch {
            var first = true
            var previous: Pair<String, String>? = null
            try {
                observeIdentity().collect { result ->
                    val identity = (result as? AppResult.Success)?.value
                    val current = identity?.let { it.sessionId to it.user.id }
                    if (!first && previous != current) inspection.reset()
                    previous = current
                    first = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional diagnostics cannot fail the application's session or delivery owner.
            } finally {
                // A dead identity observer cannot retain an old account's capture indefinitely.
                close()
            }
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { inspection.close() }
            } finally {
                lifetime.cancel()
                terminated.complete(Unit)
            }
        }
    }

    suspend fun closeAndJoin() {
        close()
        terminated.await()
        lifetime.join()
    }
}
