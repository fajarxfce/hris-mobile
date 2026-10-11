package dev.fajar.starter.workforce.presentation.calendar

import kotlinx.datetime.LocalDate

sealed interface WorkCalendarEvent {
    data object Entered : WorkCalendarEvent

    data object Stopped : WorkCalendarEvent

    data object RefreshRequested : WorkCalendarEvent

    data object CurrentWeekRequested : WorkCalendarEvent

    data class WeekRequested(val from: LocalDate) : WorkCalendarEvent

    data class EmploymentSelected(val id: String) : WorkCalendarEvent

    data class EmploymentPageRequested(val after: String?) : WorkCalendarEvent

    data object BackRequested : WorkCalendarEvent

    data object VerificationRequested : WorkCalendarEvent
}
