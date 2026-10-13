package dev.fajar.starter.payroll.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount

/** Published calculation facts. The client neither recalculates payroll nor infers settlement. */
data class PayslipBreakdown(
    val earnings: List<PayslipEarning>,
    val contributions: List<PayslipContribution>,
    val taxAllowance: DecimalAmount,
    val deductionAllowance: DecimalAmount,
    val retirementContributions: DecimalAmount,
    val qualifiedDonations: DecimalAmount,
    val otherDeductions: DecimalAmount,
    val employeeDeductions: DecimalAmount,
    val nonCashTaxable: DecimalAmount,
    val taxRule: String,
) {
    override fun toString() = "PayslipBreakdown([redacted])"
}
