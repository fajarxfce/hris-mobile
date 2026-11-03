package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** One explicit native host per application container; absence never acknowledges delivery. */
class CollaborationNotificationDelivery : AutoCloseable {
    private val state = MutableStateFlow<State>(State.Unbound)

    /** True transfers process ownership, not readiness. A rejected process remains caller-owned. */
    fun start(process: CollaborationNotificationProcess): Boolean {
        if (!state.compareAndSet(State.Unbound, State.Bound(process))) return false
        process.start()
        return true
    }

    suspend fun publish(
        parent: CollaborationNativeParent,
        candidate: CollaborationNotificationCandidate,
        allowAlert: Boolean = false,
    ): AppResult<CollaborationNotificationPublicationResult> {
        currentCoroutineContext().ensureActive()
        val captured = state.value as? State.Bound ?: return unavailable()
        val result = captured.process.publish(parent, candidate, allowAlert)
        currentCoroutineContext().ensureActive()
        return if (state.value === captured) result else unavailable()
    }

    suspend fun retry(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val captured = state.value as? State.Bound ?: return unavailable()
        val result = captured.process.retry()
        currentCoroutineContext().ensureActive()
        return if (state.value === captured) result else unavailable()
    }

    override fun close() {
        val previous =
            state.getAndUpdate {
                when (it) {
                    State.Unbound -> State.Closed(null)
                    is State.Bound -> State.Closed(it.process)
                    is State.Closed -> it
                }
            }
        (previous as? State.Bound)?.process?.close()
    }

    suspend fun closeAndJoin() {
        close()
        (state.value as? State.Closed)?.process?.closeAndJoin()
    }

    private fun unavailable() =
        AppResult.Failed(
            Failure(
                FailureKind.Unavailable,
                "Chat notifications are pending.",
                code = "client.collaboration_notification_host_unavailable",
            )
        )

    private sealed interface State {
        data object Unbound : State

        class Bound(val process: CollaborationNotificationProcess) : State

        class Closed(val process: CollaborationNotificationProcess?) : State
    }
}
