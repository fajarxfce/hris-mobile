package dev.fajar.starter.workforce.presentation.overtime.detail

sealed interface OvertimeRequestEvent {
    data object Entered : OvertimeRequestEvent

    data object Stopped : OvertimeRequestEvent

    data object RefreshRequested : OvertimeRequestEvent

    data object BackRequested : OvertimeRequestEvent

    data object VerificationRequested : OvertimeRequestEvent

    data class HistoryPageRequested(val after: Long?) : OvertimeRequestEvent
}
