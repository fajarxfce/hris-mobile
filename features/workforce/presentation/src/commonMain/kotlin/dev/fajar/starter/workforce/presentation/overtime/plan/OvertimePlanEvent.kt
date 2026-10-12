package dev.fajar.starter.workforce.presentation.overtime.plan

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimePreparation

sealed interface OvertimePlanEvent {
    data object Entered : OvertimePlanEvent

    data object Stopped : OvertimePlanEvent

    data object ReloadRequested : OvertimePlanEvent

    data class DatePickerRequested(val field: OvertimePlanDateField) : OvertimePlanEvent

    data class DateMonthRequested(val offset: Int) : OvertimePlanEvent

    data class DateSelected(val day: Int) : OvertimePlanEvent

    data class TimePickerRequested(val field: OvertimeTimeField) : OvertimePlanEvent

    data class HoursChanged(val value: String) : OvertimePlanEvent

    data class MinutesChanged(val value: String) : OvertimePlanEvent

    data object TimeConfirmed : OvertimePlanEvent

    data class BreakChanged(val value: String) : OvertimePlanEvent

    data class ReasonChanged(val value: String) : OvertimePlanEvent

    data class ReviewExpired(val prepared: OvertimePreparation) : OvertimePlanEvent

    data object SubmitRequested : OvertimePlanEvent

    data object RetryRequested : OvertimePlanEvent

    data object OpenRequestRequested : OvertimePlanEvent

    data object DiscardRequested : OvertimePlanEvent

    data object DiscardConfirmed : OvertimePlanEvent

    data object DialogDismissed : OvertimePlanEvent

    data object BackRequested : OvertimePlanEvent

    data object VerificationRequested : OvertimePlanEvent
}
