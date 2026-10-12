package dev.fajar.starter.people.presentation.directory

sealed interface EmployeesEffect {
    data object OpenSearch : EmployeesEffect

    data object Back : EmployeesEffect

    data object ScrollToStart : EmployeesEffect

    data object VerifyAccount : EmployeesEffect
}
