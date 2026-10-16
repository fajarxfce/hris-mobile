package dev.fajar.starter.workforce.presentation.overtime.action

sealed interface OvertimeActionEvent {
    data object Entered : OvertimeActionEvent

    data object Stopped : OvertimeActionEvent

    data object RefreshRequested : OvertimeActionEvent

    data class DatePickerRequested(val field: OvertimeActualField) : OvertimeActionEvent

    data class DateMonthRequested(val offset: Int) : OvertimeActionEvent

    data class DateSelected(val day: Int) : OvertimeActionEvent

    data class TimePickerRequested(val field: OvertimeActualField) : OvertimeActionEvent

    data class HoursChanged(val value: String) : OvertimeActionEvent

    data class MinutesChanged(val value: String) : OvertimeActionEvent

    data object TimeConfirmed : OvertimeActionEvent

    data class BreakChanged(val value: String) : OvertimeActionEvent

    data class ReasonChanged(val value: String) : OvertimeActionEvent

    data object SubmitRequested : OvertimeActionEvent

    data object RetryRequested : OvertimeActionEvent

    data object OpenRequestRequested : OvertimeActionEvent

    data object DiscardRequested : OvertimeActionEvent

    data object DiscardConfirmed : OvertimeActionEvent

    data object DialogDismissed : OvertimeActionEvent

    data object BackRequested : OvertimeActionEvent

    data object VerificationRequested : OvertimeActionEvent

    data object SaveRetryRequested : OvertimeActionEvent

    data object ReloadDraftRequested : OvertimeActionEvent

    data object ReloadDraftConfirmed : OvertimeActionEvent

    data object DiscardDraftRequested : OvertimeActionEvent

    data object DiscardDraftConfirmed : OvertimeActionEvent

    data object ExitWithoutSaving : OvertimeActionEvent
}
