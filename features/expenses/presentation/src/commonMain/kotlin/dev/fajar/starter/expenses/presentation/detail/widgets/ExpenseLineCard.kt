package dev.fajar.starter.expenses.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.presentation.detail.ExpenseLineItem
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ExpenseLineCard(line: ExpenseLineItem) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
        AppText(
            formatLocalDate(line.date.toString(), appLanguageTag()),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
        AppText(line.description, style = FluentTheme.typography.bodyStrong)
        AppText(
            "IDR " + formatDecimal(line.amount, appLanguageTag()),
            style = FluentTheme.typography.subtitle.copy(fontFeatureSettings = "tnum"),
        )
        line.category?.let { AppText(it, color = AppColors.muted) }
        line.costCenter?.let { AppText(it, color = AppColors.muted) }
        if (line.receiptCount > 0 || line.receipts.isNotEmpty())
            AppText(
                appString(
                    AppString.ExpenseReceiptCount,
                    formatNumber(line.receiptCount.toLong(), appLanguageTag()),
                ),
                Modifier.padding(top = 8.dp),
                style = FluentTheme.typography.caption,
            )
        line.receipts.forEach { receipt ->
            AppText(receipt.fileName, style = FluentTheme.typography.caption)
            if (receipt.possibleDuplicate)
                AppText(
                    appString(AppString.ExpenseDuplicateReceipt),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
        }
    }
}
