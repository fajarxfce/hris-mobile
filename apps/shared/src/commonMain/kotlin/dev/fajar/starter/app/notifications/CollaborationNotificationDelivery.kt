package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationNotificationActionToken
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.ActionNotificationInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One explicit native host per application container; absence never acknowledges delivery. */
class CollaborationNotificationDelivery : AutoCloseable {
    private val state = MutableStateFlow<State>(State.Unbound)

    /** True transfers process ownership, not readiness. A rejected process remains caller-owned. */
    fun start(
        process: CollaborationNotificationProcess,
        ready: StateFlow<Boolean> = MutableStateFlow(true),
    ): Boolean {
        if (!state.compareAndSet(State.Unbound, State.Bound(process, ready))) return false
        process.start()
        return true
    }

    suspend fun publish(
        parent: CollaborationNativeParent,
        candidate: CollaborationNotificationCandidate,
        allowAlert: Boolean = false,
    ): AppResult<CollaborationNotificationPublicationResult> = withProcess {
        publish(parent, candidate, allowAlert)
    }

    suspend fun act(
        input: ActionNotificationInput
    ): AppResult<CollaborationNotificationActionOutcome> = withProcess { act(input) }

    suspend fun acknowledgeReply(token: CollaborationNotificationActionToken): AppResult<Unit> =
        withProcess {
            acknowledgeReply(token)
        }

    private suspend fun <T> withProcess(
        operation: suspend CollaborationNotificationProcess.() -> AppResult<T>
    ): AppResult<T> {
        currentCoroutineContext().ensureActive()
        val captured = state.value as? State.Bound ?: return unavailable()
        // One caller deadline covers both restored-session readiness and the operation.
        val result =
            withTimeoutOrNull(8_000) {
                combine(state, captured.ready) { current, ready -> current !== captured || ready }
                    .first { it }
                if (state.value !== captured || !captured.ready.value)
                    return@withTimeoutOrNull unavailable()
                captured.process.operation()
            } ?: unavailable(FailureKind.Timeout)
        currentCoroutineContext().ensureActive()
        return if (state.value === captured) result else unavailable()
    }

    suspend fun retry(): AppResult<Unit> = withProcess { retry() }

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

    private fun unavailable(kind: FailureKind = FailureKind.Unavailable) =
        AppResult.Failed(
            Failure(
                kind,
                "Chat notifications are pending.",
                code = "client.collaboration_notification_host_unavailable",
            )
        )

    private sealed interface State {
        data object Unbound : State

        class Bound(val process: CollaborationNotificationProcess, val ready: StateFlow<Boolean>) :
            State

        class Closed(val process: CollaborationNotificationProcess?) : State
    }
}
