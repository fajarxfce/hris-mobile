package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.domain.entities.Payslip
import io.github.composefluent.FluentTheme

@Composable
fun PayslipSummaryCard(payslip: Payslip) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
        AppHeading(
            formatMonth("${payslip.summary.month}-01", appLanguageTag()),
            style = FluentTheme.typography.subtitle,
        )
        AppText(payslip.summary.employeeName + " · " + payslip.summary.employeeNumber)
        AppText(payslip.companyName, color = AppColors.muted)
        AppText(
            appString(AppString.PayslipTakeHome),
            Modifier.padding(top = 12.dp),
            color = AppColors.muted,
        )
        AppText(
            payslip.summary.currency +
                " " +
                formatDecimal(payslip.summary.takeHome, appLanguageTag()),
            style = FluentTheme.typography.title.copy(fontFeatureSettings = "tnum"),
            color = AppColors.accent,
        )
    }
}
