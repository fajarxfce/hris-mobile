package dev.fajar.starter.expenses.presentation.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.expenses.presentation.overview.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight
import kotlinx.datetime.YearMonth

@Composable
fun ExpenseFilterBar(state: ExpensesState, onEvent: (ExpensesEvent) -> Unit) {
    val month = state.filters?.month
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (month != null) {
            AppText(
                appString(AppString.ExpenseCreatedMonth),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AppIconButton(
                    Icons.Regular.ChevronLeft,
                    appString(AppString.PreviousMonth),
                    { onEvent(ExpensesEvent.PreviousMonthRequested) },
                    enabled = !state.readRestricted && month > YearMonth(1900, 1),
                )
                AppTextButton(
                    formatMonth("$month-01", appLanguageTag()),
                    { onEvent(ExpensesEvent.FilterOpened(ExpenseFilter.MONTH)) },
                    enabled = !state.readRestricted && state.content != null,
                )
                AppIconButton(
                    Icons.Regular.ChevronRight,
                    appString(AppString.NextMonth),
                    { onEvent(ExpensesEvent.NextMonthRequested) },
                    enabled = !state.readRestricted && month < YearMonth(2200, 12),
                )
            }
        }
        val content = state.content?.value
        if (content != null && !state.readRestricted)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppFilterChip(
                    appString(state.filters?.status?.label() ?: AppString.ExpenseAllStatuses),
                    selected = state.filters?.status != null,
                    onClick = { onEvent(ExpensesEvent.FilterOpened(ExpenseFilter.STATUS)) },
                    enabled = !state.readRestricted,
                )
                if (
                    content.directory.items.size > 1 ||
                        content.directory.nextCursor != null ||
                        content.selection.employmentAfter != null
                )
                    AppFilterChip(
                        appString(AppString.Employment),
                        selected = true,
                        onClick = { onEvent(ExpensesEvent.FilterOpened(ExpenseFilter.EMPLOYMENT)) },
                        enabled = !state.readRestricted,
                    )
            }
    }
}
