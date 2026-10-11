package dev.fajar.starter.people.presentation.directory

sealed interface EmployeesEvent {
    data object Entered : EmployeesEvent

    data object Stopped : EmployeesEvent

    data class QueryChanged(val value: String) : EmployeesEvent

    data class PageRequested(val after: String?) : EmployeesEvent

    data object FilterRequested : EmployeesEvent

    data object FilterDismissed : EmployeesEvent

    data class DepartmentQueryChanged(val value: String) : EmployeesEvent

    data class DepartmentSelected(val id: String?) : EmployeesEvent

    data object DepartmentRetryRequested : EmployeesEvent

    data object RefreshRequested : EmployeesEvent

    data object BackRequested : EmployeesEvent

    data object VerificationRequested : EmployeesEvent
}
