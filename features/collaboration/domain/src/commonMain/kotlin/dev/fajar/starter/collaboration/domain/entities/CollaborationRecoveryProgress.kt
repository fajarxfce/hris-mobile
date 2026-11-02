package dev.fajar.starter.collaboration.domain.entities

/** Local account ownership only. This does not admit a native session or any company. */
data class CollaborationRecoveryAccess(
    val sessionId: String,
    val audience: String,
    val accountId: String,
) {
    override fun toString() = "CollaborationRecoveryAccess([redacted])"
}

data class CollaborationRecoverySweep(
    val wakeGeneration: String,
    val afterCompanyId: String? = null,
    val inFlightCompanyId: String? = null,
    val retryRequired: Boolean = false,
)

data class CollaborationRecoveryProgress(
    val generation: String,
    val sweep: CollaborationRecoverySweep?,
)

data class CollaborationRecoverySnapshot(
    val access: CollaborationRecoveryAccess,
    val progress: CollaborationRecoveryProgress?,
)

sealed interface CollaborationRecoveryChange {
    data class Begin(val wake: CollaborationRecoveryWake) : CollaborationRecoveryChange

    /**
     * Persist before attempting this company; its previous incomplete attempt remains uncertain.
     */
    data class Attempt(val companyId: String) : CollaborationRecoveryChange

    data class Complete(val companyId: String, val retryRequired: Boolean) :
        CollaborationRecoveryChange

    /** Only the single processing owner may finish after reaching the validated directory end. */
    data object Finish : CollaborationRecoveryChange
}

data class CollaborationRecoveryUpdate(
    val snapshot: CollaborationRecoverySnapshot,
    /** Non-null only after a clean sweep is durably finished; global acknowledgment is separate. */
    val completedWake: CollaborationRecoveryWake?,
)
