package dev.fajar.starter.leave.presentation.overview

import dev.fajar.starter.leave.domain.entities.LeaveStatus

sealed interface LeaveEvent {
    data object Entered : LeaveEvent

    data object Stopped : LeaveEvent

    data object RefreshRequested : LeaveEvent

    data object BackRequested : LeaveEvent

    data object VerificationRequested : LeaveEvent

    data class YearRequested(val year: Int) : LeaveEvent

    data object StatusFilterRequested : LeaveEvent

    data class StatusSelected(val status: LeaveStatus?) : LeaveEvent

    data object EmploymentFilterRequested : LeaveEvent

    data class EmploymentSelected(val id: String) : LeaveEvent

    data class EmploymentPageRequested(val after: String?) : LeaveEvent

    data class BalancesPageRequested(val after: String?) : LeaveEvent

    data class RequestsPageRequested(val after: String?) : LeaveEvent

    data object DialogDismissed : LeaveEvent
}
