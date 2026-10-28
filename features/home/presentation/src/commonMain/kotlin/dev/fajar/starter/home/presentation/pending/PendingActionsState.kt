package dev.fajar.starter.home.presentation.pending

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workspace.domain.entities.PendingActionGroup
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary

data class PendingActionsState(val companyName: String, val rows: List<PendingActionsRowState>) {
    val needsVerification: Boolean
        get() =
            rows.any {
                it.failure?.code in
                    setOf("mfa_required", "mfa_setup_required", "mfa_verification_required")
            }
}

data class PendingActionsRowState(
    val group: PendingActionGroup,
    val summary: PendingActionSummary? = null,
    val loading: Boolean = true,
    val failure: Failure? = null,
)
