package dev.fajar.starter.expenses.presentation.item.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.formatters.receiptLabel
import dev.fajar.starter.expenses.presentation.item.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Attach
import io.github.composefluent.icons.regular.Dismiss

@Composable
fun ExpenseItemReceipts(state: ExpenseItemState, onEvent: (ExpenseItemEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppHeading(
                appString(AppString.ExpenseReceipts),
                Modifier.weight(1f),
                style = FluentTheme.typography.bodyStrong,
            )
            AppIconButton(
                Icons.Regular.Attach,
                appString(AppString.ExpenseAttachReceipt),
                { onEvent(ExpenseItemEvent.ReceiptOpened) },
                enabled = state.canSave && state.form.receipts.size < 3,
            )
        }
        if (state.form.receipts.isEmpty())
            AppText(appString(AppString.ExpenseNoAttachedReceipts), color = AppColors.muted)
        state.form.receipts.forEach { receipt ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(receipt.fileName ?: appString(AppString.ExpenseReceiptUnavailable))
                    AppText(
                        appString(receipt.status.receiptLabel()),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
                AppIconButton(
                    Icons.Regular.Dismiss,
                    appString(
                        AppString.ExpenseRemoveReceipt,
                        receipt.fileName ?: appString(AppString.ExpenseReceipts),
                    ),
                    { onEvent(ExpenseItemEvent.ReceiptRemoved(receipt.revisionId)) },
                    enabled = state.canSave,
                )
            }
        }
    }
}
