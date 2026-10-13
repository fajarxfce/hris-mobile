package dev.fajar.starter.workspace.presentation.recovery

sealed interface WorkspaceRecoveryEvent {
    data object RetryRequested : WorkspaceRecoveryEvent

    data object CompanyChangeRequested : WorkspaceRecoveryEvent
}
