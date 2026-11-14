package dev.fajar.starter.collaboration.domain.entities

/** Local progress only; no outcome is a transport delivery receipt. */
sealed interface CollaborationSpaceRecipientTransfer {
    /** Null means no canonical target is known; a retained target must never be reallocated. */
    data class WaitingForConversation(val conversationId: String?) :
        CollaborationSpaceRecipientTransfer {
        override fun toString() = "WaitingForConversation([redacted])"
    }

    data class WaitingForInbox(val conversationId: String) : CollaborationSpaceRecipientTransfer {
        override fun toString() = "WaitingForInbox([redacted])"
    }

    data class Transferred(val conversationId: String) : CollaborationSpaceRecipientTransfer {
        override fun toString() = "Transferred([redacted])"
    }
}
