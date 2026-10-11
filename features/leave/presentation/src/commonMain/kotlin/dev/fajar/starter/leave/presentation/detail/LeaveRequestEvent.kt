package dev.fajar.starter.leave.presentation.detail

sealed interface LeaveRequestEvent {
    data object Entered : LeaveRequestEvent

    data object Stopped : LeaveRequestEvent

    data object RefreshRequested : LeaveRequestEvent

    data object BackRequested : LeaveRequestEvent

    data object VerificationRequested : LeaveRequestEvent

    data class HistoryPageRequested(val after: String?) : LeaveRequestEvent
}
