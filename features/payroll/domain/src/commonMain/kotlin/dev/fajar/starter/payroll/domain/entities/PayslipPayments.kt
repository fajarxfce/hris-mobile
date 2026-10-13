package dev.fajar.starter.payroll.domain.entities

data class PayslipPayments(
    val payslipId: String,
    val version: Long,
    val attempts: List<PayslipPaymentAttempt>,
)
