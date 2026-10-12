package dev.fajar.starter.workforce.presentation.overtime.overview

import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeStatus
import kotlinx.datetime.LocalDate

sealed interface OvertimeEvent {
    data object Entered : OvertimeEvent

    data object Stopped : OvertimeEvent

    data object RefreshRequested : OvertimeEvent

    data object BackRequested : OvertimeEvent

    data object VerificationRequested : OvertimeEvent

    data object MonthFilterRequested : OvertimeEvent

    data class MonthRequested(val month: LocalDate) : OvertimeEvent

    data class PickerYearRequested(val year: Int) : OvertimeEvent

    data class PickerMonthSelected(val month: Int) : OvertimeEvent

    data object StatusFilterRequested : OvertimeEvent

    data class StatusSelected(val status: OvertimeStatus?) : OvertimeEvent

    data object EmploymentFilterRequested : OvertimeEvent

    data class EmploymentSelected(val id: String) : OvertimeEvent

    data class EmploymentPageRequested(val after: String?) : OvertimeEvent

    data class RequestsPageRequested(val after: String?) : OvertimeEvent

    data object DialogDismissed : OvertimeEvent

    data class RequestSelected(val id: String) : OvertimeEvent
}
