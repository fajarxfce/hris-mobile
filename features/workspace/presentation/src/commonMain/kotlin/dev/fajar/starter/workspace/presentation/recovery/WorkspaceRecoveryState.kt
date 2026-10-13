package dev.fajar.starter.workspace.presentation.recovery

import dev.fajar.starter.common.result.Failure

data class WorkspaceRecoveryState(
    val refreshing: Boolean,
    val changingCompany: Boolean,
    val failure: Failure?,
)
