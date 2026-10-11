package dev.fajar.starter.leave.presentation.detail

import dev.fajar.starter.leave.domain.entities.LeaveRequestAction

sealed interface LeaveRequestEvent {
    data object Entered : LeaveRequestEvent

    data object Stopped : LeaveRequestEvent

    data object RefreshRequested : LeaveRequestEvent

    data object BackRequested : LeaveRequestEvent

    data object VerificationRequested : LeaveRequestEvent

    data class HistoryPageRequested(val after: String?) : LeaveRequestEvent

    data class ActionRequested(val action: LeaveRequestAction) : LeaveRequestEvent

    data class ReasonChanged(val value: String) : LeaveRequestEvent

    data object ActionConfirmed : LeaveRequestEvent

    data object ActionDismissed : LeaveRequestEvent

    data object RetryActionRequested : LeaveRequestEvent

    data object ReviewActionRequested : LeaveRequestEvent

    data object DiscardActionRequested : LeaveRequestEvent
}
