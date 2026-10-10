package dev.fajar.starter.workspace.presentation.selection

sealed interface WorkspaceEffect {
    data object VerificationRequired : WorkspaceEffect
}
