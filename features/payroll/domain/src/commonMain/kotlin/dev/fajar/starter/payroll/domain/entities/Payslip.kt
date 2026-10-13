package dev.fajar.starter.payroll.domain.entities

data class Payslip(
    val summary: PayslipSummary,
    val companyCode: String,
    val companyName: String,
    val breakdown: PayslipBreakdown,
) {
    override fun toString() = "Payslip([redacted])"
}
