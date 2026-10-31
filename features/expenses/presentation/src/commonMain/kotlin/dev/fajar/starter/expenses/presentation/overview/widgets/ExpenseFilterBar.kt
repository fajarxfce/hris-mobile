package dev.fajar.starter.expenses.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.expenses.presentation.overview.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import kotlinx.datetime.YearMonth

@Composable
fun ExpenseFilterBar(state: ExpensesState, onEvent: (ExpensesEvent) -> Unit) {
    val month = state.filters?.month
    val content = state.content?.value
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (month != null) {
            AppText(
                appString(AppString.ExpenseCreatedMonth),
                Modifier.padding(start = 4.dp),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            AppPeriodNavigation(
                appString(AppString.PreviousMonth),
                appString(AppString.NextMonth),
                { onEvent(ExpensesEvent.PreviousMonthRequested) },
                { onEvent(ExpensesEvent.NextMonthRequested) },
                Modifier.fillMaxWidth(),
                previousEnabled = !state.readRestricted && month > YearMonth(1900, 1),
                nextEnabled = !state.readRestricted && month < YearMonth(2200, 12),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFilterChip(
                        formatMonth("$month-01", appLanguageTag()),
                        false,
                        { onEvent(ExpensesEvent.FilterOpened(ExpenseFilter.MONTH)) },
                        enabled = !state.readRestricted && content != null,
                        icon = null,
                    )
                    ExpenseStatusFilter(state, onEvent)
                }
            }
        }
        if (month == null) ExpenseStatusFilter(state, onEvent)
        if (
            content != null &&
                !state.readRestricted &&
                (content.directory.items.size > 1 ||
                    content.directory.nextCursor != null ||
                    content.selection.employmentAfter != null)
        )
            AppFilterChip(
                appString(AppString.Employment),
                true,
                { onEvent(ExpensesEvent.FilterOpened(ExpenseFilter.EMPLOYMENT)) },
            )
    }
}

@Composable
private fun ExpenseStatusFilter(state: ExpensesState, onEvent: (ExpensesEvent) -> Unit) {
    if (state.content != null && !state.readRestricted)
        AppFilterChip(
            appString(state.filters?.status?.label() ?: AppString.ExpenseAllStatuses),
            selected = state.filters?.status != null,
            onClick = { onEvent(ExpensesEvent.FilterOpened(ExpenseFilter.STATUS)) },
            icon = null,
        )
}
