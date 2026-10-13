package dev.fajar.starter.payroll.presentation.detail.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.payroll.domain.entities.Payslip
import dev.fajar.starter.payroll.presentation.formatters.label
import io.github.composefluent.FluentTheme

@Composable
fun PayslipEarningsCard(payslip: Payslip) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 14.dp) {
        AppHeading(appString(AppString.PayslipEarnings), style = FluentTheme.typography.bodyStrong)
        for (earning in payslip.breakdown.earnings) PayslipAmountRow(
            earning.name ?: appString(earning.kind.label()),
            earning.amount,
        )
        if (!payslip.breakdown.taxAllowance.isZero)
            PayslipAmountRow(
                appString(AppString.PayslipTaxAllowance),
                payslip.breakdown.taxAllowance,
            )
        if (!payslip.breakdown.deductionAllowance.isZero)
            PayslipAmountRow(
                appString(AppString.PayslipDeductionAllowance),
                payslip.breakdown.deductionAllowance,
            )
    }
}

@Composable
fun PayslipDeductionsCard(payslip: Payslip) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 14.dp) {
        AppHeading(
            appString(AppString.PayslipDeductions),
            style = FluentTheme.typography.bodyStrong,
        )
        PayslipAmountRow(
            appString(AppString.PayslipRetirement),
            payslip.breakdown.retirementContributions,
        )
        if (!payslip.breakdown.qualifiedDonations.isZero)
            PayslipAmountRow(
                appString(AppString.PayslipDonations),
                payslip.breakdown.qualifiedDonations,
            )
        PayslipAmountRow(
            appString(AppString.PayslipOtherDeductions),
            payslip.breakdown.otherDeductions,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.border))
        PayslipAmountRow(
            appString(AppString.PayslipEmployeeDeductions),
            payslip.breakdown.employeeDeductions,
            emphasized = true,
        )
        PayslipAmountRow(appString(AppString.PayslipWithheld), payslip.summary.withheld)
    }
}

@Composable
fun PayslipEmployerCard(payslip: Payslip) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 14.dp) {
        AppHeading(
            appString(AppString.PayslipEmployerContributions),
            style = FluentTheme.typography.bodyStrong,
        )
        for (contribution in payslip.breakdown.contributions) PayslipAmountRow(
            appString(contribution.program.label()),
            contribution.employerAmount,
        )
    }
}

@Composable
fun PayslipTaxCard(payslip: Payslip) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 14.dp) {
        AppHeading(
            appString(AppString.PayslipTaxSummary),
            style = FluentTheme.typography.bodyStrong,
        )
        PayslipAmountRow(appString(AppString.PayslipGross), payslip.summary.taxableGross)
        if (!payslip.breakdown.nonCashTaxable.isZero)
            PayslipAmountRow(appString(AppString.PayslipNonCash), payslip.breakdown.nonCashTaxable)
    }
}
