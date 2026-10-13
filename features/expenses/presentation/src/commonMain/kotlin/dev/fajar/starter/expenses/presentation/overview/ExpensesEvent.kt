package dev.fajar.starter.expenses.presentation.overview

import dev.fajar.starter.expenses.domain.entities.ExpenseStatus
import kotlinx.datetime.YearMonth

sealed interface ExpensesEvent {
    data object Entered : ExpensesEvent

    data object Stopped : ExpensesEvent

    data object RefreshRequested : ExpensesEvent

    data object PreviousMonthRequested : ExpensesEvent

    data object NextMonthRequested : ExpensesEvent

    data class FilterOpened(val filter: ExpenseFilter) : ExpensesEvent

    data object FilterDismissed : ExpensesEvent

    data class PickerYearRequested(val year: Int) : ExpensesEvent

    data class MonthSelected(val month: YearMonth) : ExpensesEvent

    data class StatusSelected(val status: ExpenseStatus?) : ExpensesEvent

    data class EmploymentSelected(val id: String) : ExpensesEvent

    data class EmploymentPageRequested(val after: String?) : ExpensesEvent

    data class PageRequested(val after: String?) : ExpensesEvent

    data class ClaimSelected(val id: String) : ExpensesEvent

    data object BackRequested : ExpensesEvent

    data object VerificationRequested : ExpensesEvent
}
