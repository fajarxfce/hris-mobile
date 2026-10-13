package dev.fajar.starter.expenses.presentation.item.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.expenses.presentation.formatters.*
import dev.fajar.starter.expenses.presentation.item.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSync
import kotlinx.datetime.Month

@Composable
fun ExpenseItemPicker(state: ExpenseItemState, onEvent: (ExpenseItemEvent) -> Unit) {
    val dialog = state.dialog ?: return
    AppDialog(
        appString(
            when (dialog) {
                is ExpenseItemDialog.Date -> AppString.ExpenseDate
                ExpenseItemDialog.Categories -> AppString.ExpenseCategory
                ExpenseItemDialog.CostCenters -> AppString.ExpenseCostCenter
                ExpenseItemDialog.Exit -> AppString.ExpenseDiscardChanges
            }
        ),
        appString(AppString.Close),
        { onEvent(ExpenseItemEvent.DialogDismissed) },
    ) {
        if (dialog == ExpenseItemDialog.CostCenters)
            AppSearchField(
                state.centerQuery,
                { onEvent(ExpenseItemEvent.CostCenterQueryChanged(it)) },
                appString(AppString.ExpenseSearchCostCenter),
                appString(AppString.ClearSearch),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                enabled = state.editable,
            )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.choicesLoading) item(key = "loading") { AppLoading(compact = true) }
            if (dialog == ExpenseItemDialog.Categories || dialog == ExpenseItemDialog.CostCenters) {
                state.visibleFailure?.let { failure ->
                    item(key = "failure") { AppFeedback(failureText(failure)) }
                }
                item(key = "refresh") {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(ExpenseItemEvent.ReloadRequested) },
                        enabled = !state.loading && !state.choicesLoading,
                    )
                }
            }
            when (dialog) {
                is ExpenseItemDialog.Date ->
                    item(key = "date") {
                        AppDatePicker(
                            formatMonth(dialog.month.toString(), appLanguageTag()),
                            expenseWeekdays.map { appString(it) },
                            expenseCalendarDates(dialog, state.form, appLanguageTag()),
                            { onEvent(ExpenseItemEvent.DateSelected(it)) },
                            appString(AppString.PreviousMonth),
                            appString(AppString.NextMonth),
                            if (dialog.month.year == 1900 && dialog.month.month == Month.JANUARY)
                                null
                            else {
                                { onEvent(ExpenseItemEvent.DateMonthChanged(-1)) }
                            },
                            if (dialog.month.year == 2200 && dialog.month.month == Month.DECEMBER)
                                null
                            else {
                                { onEvent(ExpenseItemEvent.DateMonthChanged(1)) }
                            },
                        )
                    }
                ExpenseItemDialog.Categories -> {
                    if (!state.choicesLoading && state.categories?.page?.items?.isEmpty() == true)
                        item(key = "empty") { AppText(appString(AppString.ExpenseNoCategories)) }
                    items(state.categories?.page?.items.orEmpty(), key = { it.id }) { category ->
                        AppRadioRow(
                            category.name,
                            state.form.category?.id == category.id,
                            { onEvent(ExpenseItemEvent.CategorySelected(category.id)) },
                            enabled = state.editable && !state.choicesLoading && category.active,
                        )
                    }
                    state.categories?.let { page ->
                        item(key = "paging") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (page.after != null)
                                    AppTextButton(
                                        appString(AppString.ExpenseFirstOptions),
                                        { onEvent(ExpenseItemEvent.CategoryPageRequested(null)) },
                                        enabled = !state.choicesLoading,
                                    )
                                page.page.nextCursor?.let { after ->
                                    AppTextButton(
                                        appString(AppString.ExpenseMoreOptions),
                                        { onEvent(ExpenseItemEvent.CategoryPageRequested(after)) },
                                        enabled = !state.choicesLoading,
                                    )
                                }
                            }
                        }
                    }
                }
                ExpenseItemDialog.CostCenters -> {
                    item(key = "none") {
                        AppRadioRow(
                            appString(AppString.ExpenseNoCostCenter),
                            state.form.costCenter == null,
                            { onEvent(ExpenseItemEvent.CostCenterSelected(null)) },
                            enabled = state.editable && !state.choicesLoading,
                        )
                    }
                    if (!state.choicesLoading && state.costCenters?.page?.items?.isEmpty() == true)
                        item(key = "empty") { AppText(appString(AppString.ExpenseNoCostCenters)) }
                    items(state.costCenters?.page?.items.orEmpty(), key = { it.id }) { center ->
                        AppRadioRow(
                            center.name,
                            state.form.costCenter?.id == center.id,
                            { onEvent(ExpenseItemEvent.CostCenterSelected(center.id)) },
                            enabled = state.editable && !state.choicesLoading,
                        )
                    }
                    state.costCenters?.let { page ->
                        item(key = "paging") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (page.after != null)
                                    AppTextButton(
                                        appString(AppString.ExpenseFirstOptions),
                                        { onEvent(ExpenseItemEvent.CostCenterPageRequested(null)) },
                                        enabled = !state.choicesLoading,
                                    )
                                page.page.nextCursor?.let { after ->
                                    AppTextButton(
                                        appString(AppString.ExpenseMoreOptions),
                                        {
                                            onEvent(ExpenseItemEvent.CostCenterPageRequested(after))
                                        },
                                        enabled = !state.choicesLoading,
                                    )
                                }
                            }
                        }
                    }
                }
                ExpenseItemDialog.Exit ->
                    item(key = "exit") {
                        AppText(appString(AppString.ExpenseUnsavedChanges))
                        AppButton(
                            appString(AppString.ExpenseDiscard),
                            { onEvent(ExpenseItemEvent.ExitConfirmed) },
                            Modifier.fillMaxWidth(),
                        )
                    }
            }
        }
    }
}
