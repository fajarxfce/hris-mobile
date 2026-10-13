package dev.fajar.starter.expenses.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.ExpensePaymentAttempt
import dev.fajar.starter.expenses.presentation.formatters.label
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ExpensePaymentCard(payments: List<ExpensePaymentAttempt>) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
        AppHeading(appString(AppString.ExpensePayment), style = FluentTheme.typography.bodyStrong)
        if (payments.isEmpty())
            AppText(appString(AppString.ExpenseNoPayment), color = AppColors.muted)
        payments.forEach { payment ->
            AppBadge(appString(payment.status.label()))
            AppText(
                formatDate(
                    (payment.settledAt ?: payment.releasedAt ?: payment.createdAt)
                        .toEpochMilliseconds(),
                    appLanguageTag(),
                ),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
        }
    }
}
