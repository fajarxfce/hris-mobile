package dev.fajar.starter.people.presentation.directory

sealed interface EmployeesEvent {
    data object Entered : EmployeesEvent

    data object Stopped : EmployeesEvent

    data class QueryChanged(val value: String) : EmployeesEvent

    data class PageRequested(val after: String?) : EmployeesEvent

    data object RefreshRequested : EmployeesEvent

    data object BackRequested : EmployeesEvent

    data object VerificationRequested : EmployeesEvent
}
