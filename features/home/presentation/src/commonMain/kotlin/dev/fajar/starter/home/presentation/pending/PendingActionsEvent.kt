package dev.fajar.starter.home.presentation.pending

import dev.fajar.starter.workspace.domain.entities.PendingActionGroup

sealed interface PendingActionsEvent {
    data object Entered : PendingActionsEvent

    data object Stopped : PendingActionsEvent

    data object ReloadRequested : PendingActionsEvent

    data class OpenRequested(val group: PendingActionGroup) : PendingActionsEvent

    data object VerificationRequested : PendingActionsEvent

    data object BackRequested : PendingActionsEvent
}
