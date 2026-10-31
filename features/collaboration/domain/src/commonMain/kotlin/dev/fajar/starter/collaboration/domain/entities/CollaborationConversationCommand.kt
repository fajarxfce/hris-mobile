package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

sealed interface CollaborationConversationAction {
    data class Open(val peerAccountId: String) : CollaborationConversationAction

    data class RequestRoom(
        val conversationId: String,
        val deviceId: String,
        val deviceVersion: Long,
        val provisioningVersion: Long?,
    ) : CollaborationConversationAction
}

fun CollaborationConversationAction.resourceId(): String =
    when (this) {
        is CollaborationConversationAction.Open -> "direct/$peerAccountId"
        is CollaborationConversationAction.RequestRoom -> "room/$conversationId"
    }

/** Immutable control intent. Unlike gateway bootstrap, these API payloads do not bind a build. */
data class CollaborationConversationCommand(
    val id: String,
    val binding: CollaborationSetupBinding,
    val createdAt: Instant,
    val action: CollaborationConversationAction,
) {
    override fun toString() = "CollaborationConversationCommand([redacted])"
}

sealed interface CollaborationConversationOutcome {
    data object Pending : CollaborationConversationOutcome

    /** Stored before HTTP; failure cannot prove that an earlier attempt did not commit. */
    data class Unconfirmed(val failure: Failure? = null) : CollaborationConversationOutcome

    data class Confirmed(val receipt: CollaborationSetupReceipt) : CollaborationConversationOutcome
}

data class CollaborationConversationCommandRecord(
    val command: CollaborationConversationCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: CollaborationConversationOutcome,
) {
    override fun toString() =
        "CollaborationConversationCommandRecord(revision=$revision, attempts=$attempts)"
}
