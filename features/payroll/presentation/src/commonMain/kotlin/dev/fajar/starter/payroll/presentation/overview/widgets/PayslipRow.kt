package dev.fajar.starter.payroll.presentation.overview.widgets

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.domain.entities.PayslipSummary
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun PayslipRow(payslip: PayslipSummary, onClick: () -> Unit) {
    AppCard(padding = 0.dp) {
        AppListRow(
            title = formatMonth("${payslip.month}-01", appLanguageTag()),
            supportingText = payslip.employeeName + " · " + payslip.employeeNumber,
            emphasized = true,
            onClick = onClick,
            metadata = {
                AppText(
                    appString(AppString.PayslipTakeHome),
                    Modifier.padding(top = 8.dp),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
                AppText(
                    payslip.currency + " " + formatDecimal(payslip.takeHome, appLanguageTag()),
                    style = FluentTheme.typography.bodyStrong.copy(fontFeatureSettings = "tnum"),
                )
            },
            trailing = { AppIcon(Icons.Regular.ChevronRight) },
        )
    }
}
