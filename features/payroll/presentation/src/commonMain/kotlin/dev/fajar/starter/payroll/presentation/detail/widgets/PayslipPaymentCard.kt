package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.domain.entities.PayslipView
import dev.fajar.starter.payroll.presentation.formatters.label
import io.github.composefluent.FluentTheme

@Composable
fun PayslipPaymentCard(view: PayslipView) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 10.dp) {
        AppHeading(appString(AppString.PayslipPayment), style = FluentTheme.typography.bodyStrong)
        if (view.payments.attempts.isEmpty()) AppText(appString(AppString.PayslipNoPayment))
        for (attempt in view.payments.attempts) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppBadge(appString(attempt.status.label()))
                AppText(
                    formatDate(
                        (attempt.resolvedAt ?: attempt.releasedAt ?: attempt.createdAt)
                            .toEpochMilliseconds(),
                        appLanguageTag(),
                    ),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        }
        AppText(
            appString(
                AppString.PayslipScheduledPayment,
                formatLocalDate(
                    view.payslip.summary.plannedPaymentDate.toString(),
                    appLanguageTag(),
                ),
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    }
}
