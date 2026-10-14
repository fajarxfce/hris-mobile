package dev.fajar.starter.expenses.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.expenses.domain.entities.ExpenseStatus
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.expenses.presentation.overview.*
import dev.fajar.starter.localization.*
import kotlinx.datetime.YearMonth

@Composable
fun ExpenseFilterDialog(state: ExpensesState, onEvent: (ExpensesEvent) -> Unit) {
    AppDialog(
        appString(
            when (state.dialog) {
                ExpenseFilter.MONTH -> AppString.ExpenseCreatedMonth
                ExpenseFilter.STATUS -> AppString.ExpenseStatus
                else -> AppString.ChooseEmployment
            }
        ),
        appString(AppString.Close),
        { onEvent(ExpensesEvent.FilterDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) item(key = "loading") { AppLoading() }
            when (state.dialog) {
                ExpenseFilter.MONTH -> {
                    val year = state.pickerYear
                    if (year != null)
                        item(key = "month") {
                            AppMonthPicker(
                                year.toString(),
                                (1..12).map { number ->
                                    AppMonthChoice(
                                        number,
                                        formatMonth(
                                            "$year-${number.toString().padStart(2, '0')}-01",
                                            appLanguageTag(),
                                            false,
                                        ),
                                        selected = state.filters?.month == YearMonth(year, number),
                                    )
                                },
                                { onEvent(ExpensesEvent.MonthSelected(YearMonth(year, it))) },
                                appString(AppString.PreviousYear),
                                appString(AppString.NextYear),
                                if (year > 1900) {
                                    { onEvent(ExpensesEvent.PickerYearRequested(year - 1)) }
                                } else null,
                                if (year < 2200) {
                                    { onEvent(ExpensesEvent.PickerYearRequested(year + 1)) }
                                } else null,
                            )
                        }
                }
                ExpenseFilter.STATUS -> {
                    item(key = "all") {
                        AppRadioRow(
                            appString(AppString.ExpenseAllStatuses),
                            state.filters?.status == null,
                            { onEvent(ExpensesEvent.StatusSelected(null)) },
                        )
                    }
                    items(ExpenseStatus.entries, key = { it.name }) { status ->
                        AppRadioRow(
                            appString(status.label()),
                            state.filters?.status == status,
                            { onEvent(ExpensesEvent.StatusSelected(status)) },
                        )
                    }
                }
                ExpenseFilter.EMPLOYMENT -> {
                    val content = state.content?.value
                    state.failure?.let { item(key = "failure") { AppFeedback(failureText(it)) } }
                    items(content?.directory?.items.orEmpty(), key = { it.id }) { employee ->
                        AppRadioRow(
                            employee.employeeNumber,
                            employee.id == state.filters?.employeeId,
                            { onEvent(ExpensesEvent.EmploymentSelected(employee.id)) },
                        )
                    }
                    if (content?.directory?.nextCursor != null)
                        item(key = "more") {
                            AppTextButton(
                                appString(AppString.MoreEmployments),
                                {
                                    onEvent(
                                        ExpensesEvent.EmploymentPageRequested(
                                            content.directory.nextCursor
                                        )
                                    )
                                },
                                enabled = !state.readRestricted,
                            )
                        }
                    if (content?.selection?.employmentAfter != null)
                        item(key = "first") {
                            AppTextButton(
                                appString(AppString.CalendarFirstEmployments),
                                { onEvent(ExpensesEvent.EmploymentPageRequested(null)) },
                                enabled = !state.readRestricted,
                            )
                        }
                }
                null -> Unit
            }
        }
    }
}
