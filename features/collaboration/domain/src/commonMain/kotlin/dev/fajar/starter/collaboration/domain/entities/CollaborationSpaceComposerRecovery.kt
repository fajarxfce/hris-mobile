package dev.fajar.starter.collaboration.domain.entities

/** Local work remains distinct after a gesture has moved from the outbox to the message ledger. */
data class CollaborationSpaceComposerRecovery(
    val pendingSubmissions: Boolean,
    val pendingMessages: Boolean,
)
