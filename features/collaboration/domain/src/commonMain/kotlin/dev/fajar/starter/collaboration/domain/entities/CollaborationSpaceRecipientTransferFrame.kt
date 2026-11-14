package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.AppResult

/** Immutable local inputs owned by one finite recipient pass. No clocks or transport state. */
data class CollaborationSpaceRecipientTransferFrame(
    val source: CollaborationSpaceRecipientOutboxVersion?,
    val catalog: CollaborationSpaceConversationCatalogVersion?,
    val destination: CollaborationSpaceInboxVersion?,
) {
    override fun toString() = "CollaborationSpaceRecipientTransferFrame([redacted])"
}

/** Bounded attempt memory, owned by the collected worker and discarded with its account scope. */
data class CollaborationSpaceRecipientTransferAttempt(
    val submission: CollaborationSpaceRecipientSubmission,
    val frame: CollaborationSpaceRecipientTransferFrame,
    val result: AppResult<CollaborationSpaceRecipientTransfer>,
) {
    override fun toString() = "CollaborationSpaceRecipientTransferAttempt([redacted])"
}
