package dev.fajar.starter.expenses.presentation.item.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.item.*
import dev.fajar.starter.expenses.presentation.item.widgets.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun ExpenseItemPage(
    state: ExpenseItemState,
    list: LazyListState,
    onEvent: (ExpenseItemEvent) -> Unit,
) {
    AppLazyPage(
        state = list,
        maxWidth = 720.dp,
        spacing = 16.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.ExpenseItem),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(ExpenseItemEvent.BackRequested) },
                    )
                },
            )
        },
        bottomBar = {
            if (state.restriction == null)
                AppActionBar {
                    AppButton(
                        appString(AppString.ExpenseSaveItem),
                        { onEvent(ExpenseItemEvent.SaveRequested) },
                        Modifier.fillMaxWidth(),
                        enabled = state.canSave,
                    )
                }
        },
    ) {
        if (state.loading) item(key = "loading") { AppLoading(compact = true) }
        state.visibleFailure?.let { failure ->
            item(key = "failure") {
                AppFeedback(failureText(failure))
                if (state.needsVerification)
                    AppTextButton(
                        appString(AppString.VerifyAccount),
                        { onEvent(ExpenseItemEvent.VerificationRequested) },
                    )
                else if (!state.ready || state.restriction != null)
                    AppTextButton(
                        appString(AppString.TryAgain),
                        { onEvent(ExpenseItemEvent.ReloadRequested) },
                        enabled = !state.loading,
                    )
            }
        }
        if (state.restriction == null) {
            item(key = "references") {
                AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    AppListRow(
                        appString(AppString.ExpenseDate),
                        { onEvent(ExpenseItemEvent.DateOpened) },
                        supportingText =
                            formatLocalDate(state.form.date.toString(), appLanguageTag()),
                        enabled = state.canSave,
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                    AppListRow(
                        appString(AppString.ExpenseCategory),
                        { onEvent(ExpenseItemEvent.CategoryOpened) },
                        supportingText =
                            state.form.category?.name
                                ?: appString(
                                    if (state.form.category == null) AppString.ExpenseChooseCategory
                                    else AppString.ExpenseReferenceUnavailable
                                ),
                        enabled = state.canSave,
                        trailing = { AppIcon(Icons.Regular.ChevronRight) },
                    )
                }
            }
            item(key = "amount") {
                AppTextField(
                    appString(AppString.ExpenseAmount),
                    state.form.amount,
                    { onEvent(ExpenseItemEvent.AmountChanged(it)) },
                    enabled = state.canSave,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            item(key = "description") {
                AppTextField(
                    appString(AppString.ExpenseItemDescription),
                    state.form.description,
                    { onEvent(ExpenseItemEvent.DescriptionChanged(it)) },
                    enabled = state.canSave,
                    maxLines = 3,
                )
            }
            item(key = "cost-center") {
                AppListRow(
                    appString(AppString.ExpenseCostCenter),
                    { onEvent(ExpenseItemEvent.CostCenterOpened) },
                    supportingText =
                        state.form.costCenter?.name
                            ?: appString(
                                if (state.form.costCenter == null) AppString.ExpenseNoCostCenter
                                else AppString.ExpenseReferenceUnavailable
                            ),
                    enabled = state.canSave,
                    trailing = { AppIcon(Icons.Regular.ChevronRight) },
                )
            }
            item(key = "receipts") { ExpenseItemReceipts(state, onEvent) }
            state.categoryPolicy?.let { policy ->
                item(key = "policy") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppText(
                            appString(
                                AppString.ExpenseLineLimit,
                                "IDR " + formatDecimal(policy.maximumLineAmount, appLanguageTag()),
                            ),
                            color = AppColors.muted,
                        )
                        if (policy.receiptRequired)
                            AppText(
                                appString(AppString.ExpenseReceiptRequired),
                                color = AppColors.muted,
                            )
                        if (policy.costCenterRequired)
                            AppText(
                                appString(AppString.ExpenseCostCenterRequired),
                                color = AppColors.muted,
                            )
                    }
                }
            }
        }
    }
    if (state.dialog != null) ExpenseItemPicker(state, onEvent)
}
