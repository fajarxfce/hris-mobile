package dev.fajar.starter.home.presentation.pending

import dev.fajar.starter.workspace.domain.entities.PendingActionGroup

sealed interface PendingActionsEffect {
    data object Back : PendingActionsEffect

    data object VerifyAccount : PendingActionsEffect

    data class Open(val group: PendingActionGroup) : PendingActionsEffect
}
