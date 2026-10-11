package dev.fajar.starter.workforce.presentation.attendance.history

import kotlinx.datetime.LocalDate

sealed interface AttendanceHistoryEvent {
    data object Entered : AttendanceHistoryEvent

    data object Stopped : AttendanceHistoryEvent

    data object RefreshRequested : AttendanceHistoryEvent

    data object BackRequested : AttendanceHistoryEvent

    data object VerificationRequested : AttendanceHistoryEvent

    data object DateFilterRequested : AttendanceHistoryEvent

    data object DateFilterDismissed : AttendanceHistoryEvent

    data class MonthRequested(val from: LocalDate) : AttendanceHistoryEvent

    data class PickerYearRequested(val year: Int) : AttendanceHistoryEvent

    data class PickerMonthSelected(val month: Int) : AttendanceHistoryEvent

    data class EmploymentSelected(val id: String) : AttendanceHistoryEvent

    data class EmploymentPageRequested(val after: String?) : AttendanceHistoryEvent

    data class DaySelected(val date: LocalDate) : AttendanceHistoryEvent

    data object DayDismissed : AttendanceHistoryEvent
}
