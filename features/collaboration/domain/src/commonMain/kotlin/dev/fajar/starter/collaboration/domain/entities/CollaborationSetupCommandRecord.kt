package dev.fajar.starter.collaboration.domain.entities

import dev.fajar.starter.common.result.Failure

sealed interface CollaborationSetupOutcome {
    data object Pending : CollaborationSetupOutcome

    /**
     * Persisted before transport. A later rejection cannot establish that earlier I/O did not
     * commit.
     */
    data class Unconfirmed(val failure: Failure? = null) : CollaborationSetupOutcome

    data class Registered(val receipt: CollaborationSetupReceipt) : CollaborationSetupOutcome

    data class Retried(val receipt: CollaborationSetupReceipt) : CollaborationSetupOutcome

    data class Bootstrapped(val gateway: CollaborationGateway) : CollaborationSetupOutcome
}

data class CollaborationSetupCommandRecord(
    val command: CollaborationSetupCommand,
    val revision: Long,
    val attempts: Int,
    val outcome: CollaborationSetupOutcome,
) {
    override fun toString() =
        "CollaborationSetupCommandRecord(revision=$revision, attempts=$attempts)"
}

enum class CollaborationSetupInsertion {
    STORED,
    OCCUPIED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}
