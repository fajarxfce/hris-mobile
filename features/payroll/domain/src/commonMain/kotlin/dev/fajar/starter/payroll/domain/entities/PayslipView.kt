package dev.fajar.starter.payroll.domain.entities

/** Statement facts and current payment progress have independent revisions. */
data class PayslipView(val payslip: Payslip, val payments: PayslipPayments)
