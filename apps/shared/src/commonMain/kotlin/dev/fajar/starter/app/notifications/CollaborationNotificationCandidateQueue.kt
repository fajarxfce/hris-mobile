package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.policies.validCollaborationReadSelection
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** Only opaque IDs and caller-owned completions. Closing and offer share one atomic state. */
internal class CollaborationNotificationCandidateQueue {
    private val state = MutableStateFlow<State>(State.Open(emptyList()))

    fun offer(request: NotificationCandidateRequest): Boolean {
        while (true) {
            val before = state.value as? State.Open ?: return false
            val retained = before.requests.filter { it.result.isActive }
            if (retained.size >= 20) return false
            if (state.compareAndSet(before, State.Open(retained + request))) return true
        }
    }

    fun pending(): List<NotificationCandidateRequest> =
        (state.value as? State.Open)?.requests?.filter { it.result.isActive }.orEmpty()

    fun remove(request: NotificationCandidateRequest) {
        while (true) {
            val before = state.value as? State.Open ?: return
            val after = before.requests.filter { it !== request && it.result.isActive }
            if (state.compareAndSet(before, State.Open(after))) return
        }
    }

    fun close(result: AppResult<CollaborationNotificationPublicationResult>) {
        val before = state.getAndUpdate { State.Closed } as? State.Open ?: return
        before.requests.forEach { it.result.complete(result) }
    }

    private sealed interface State {
        class Open(val requests: List<NotificationCandidateRequest>) : State

        data object Closed : State
    }
}

internal class NotificationCandidateRequest(
    val parent: CollaborationNativeParent,
    val candidate: CollaborationNotificationCandidate,
    val allowAlert: Boolean,
    val result: CompletableDeferred<AppResult<CollaborationNotificationPublicationResult>>,
) {
    val key = NotificationSourceKey(candidate.companyId, candidate.conversationId)
    var busyRevision: Long? = null
}

internal data class NotificationSourceKey(val companyId: String, val conversationId: String) {
    override fun toString() = "NotificationSourceKey([redacted])"
}

internal fun validNotificationCandidate(
    parent: CollaborationNativeParent,
    candidate: CollaborationNotificationCandidate,
): Boolean =
    parent.sessionId.length in 1..128 &&
        parent.sessionId.all { it.code in 33..126 } &&
        parent.audience.length in 1..2048 &&
        validCollaborationMessageId(parent.accountId) &&
        validCollaborationMessageId(parent.nativeSessionId) &&
        validCollaborationMessageId(candidate.companyId) &&
        validCollaborationReadSelection(
            candidate.conversationId,
            candidate.roomId,
            listOf(candidate.eventId),
            false,
        )
