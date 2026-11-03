package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.coroutines.flow.*

/** Process-local route ownership, independent of read receipts and notification authority. */
class CollaborationNotificationFocus : AutoCloseable {
    internal class Snapshot(
        val owner: CollaborationOwner? = null,
        val conversationId: String? = null,
        val closed: Boolean = false,
    ) {
        fun matches(
            parent: CollaborationNativeParent,
            candidate: CollaborationNotificationCandidate,
        ) =
            owner != null &&
                owner.sessionId == parent.sessionId &&
                owner.audience == parent.audience &&
                owner.accountId == parent.accountId &&
                owner.nativeSessionId == parent.nativeSessionId &&
                owner.companyId == candidate.companyId &&
                conversationId == candidate.conversationId

        override fun toString() = "CollaborationNotificationFocus.Snapshot([redacted])"
    }

    private val state = MutableStateFlow(Snapshot())
    // Every revision matters, including a rapid enter/leave returning to an unfocused route.
    internal val changes: Flow<Unit> = state.map { Unit }

    fun register(owner: CollaborationOwner, conversationId: String): AutoCloseable? {
        if (
            !validCollaborationNotificationParent(
                CollaborationNativeParent(
                    owner.sessionId,
                    owner.audience,
                    owner.accountId,
                    owner.nativeSessionId,
                )
            ) ||
                !validCollaborationMessageId(owner.companyId) ||
                !validCollaborationMessageId(conversationId)
        )
            return null
        val next = Snapshot(owner, conversationId)
        while (true) {
            val old = state.value
            if (old.closed) return null
            if (state.compareAndSet(old, next))
                return AutoCloseable { state.compareAndSet(next, Snapshot()) }
        }
    }

    internal fun capture(): Snapshot = state.value

    internal fun current(expected: Snapshot): Boolean = !expected.closed && state.value === expected

    override fun close() {
        while (true) {
            val old = state.value
            if (old.closed || state.compareAndSet(old, Snapshot(closed = true))) return
        }
    }
}
