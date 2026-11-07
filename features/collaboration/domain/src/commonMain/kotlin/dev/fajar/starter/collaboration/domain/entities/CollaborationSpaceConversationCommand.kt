package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

/** Immutable setup intent. Current login handles, credentials and client proof stay outside it. */
sealed interface CollaborationSpaceConversationCommand {
    val id: String
    val binding: CollaborationSpaceIdentityBinding
    val peerAccountId: String
    val createdAt: Instant

    data class Open(
        override val id: String,
        override val binding: CollaborationSpaceIdentityBinding,
        override val peerAccountId: String,
        override val createdAt: Instant,
    ) : CollaborationSpaceConversationCommand {
        override fun toString() = "CollaborationSpaceConversationCommand.Open([redacted])"
    }

    data class Room(
        val intent: CollaborationSpaceRoomRequestIntent,
        override val peerAccountId: String,
        override val createdAt: Instant,
    ) : CollaborationSpaceConversationCommand {
        override val id
            get() = intent.operationId

        override val binding
            get() = intent.binding

        override fun toString() = "CollaborationSpaceConversationCommand.Room([redacted])"
    }
}

sealed interface CollaborationSpaceConversationOutcome {
    data object Pending : CollaborationSpaceConversationOutcome

    /** Must be committed before transport; losing a response does not prove non-execution. */
    data class Unconfirmed(val failure: Failure? = null) : CollaborationSpaceConversationOutcome

    /** A setup receipt, not provider readiness, content admission or a message acknowledgement. */
    data class Recorded(val receipt: CollaborationSetupReceipt) :
        CollaborationSpaceConversationOutcome

    /** Immutable original Room non-execution evidence; successor creation is separate. */
    data class Fenced(val evidence: CollaborationSpaceRoomRequestFence) :
        CollaborationSpaceConversationOutcome
}

data class CollaborationSpaceConversationCommandRecord(
    val command: CollaborationSpaceConversationCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: CollaborationSpaceConversationOutcome,
) {
    override fun toString() =
        "CollaborationSpaceConversationCommandRecord(revision=$revision, attempts=$attempts)"
}
