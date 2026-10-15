package dev.fajar.starter.payroll.domain.entities

data class PayslipPayments(
    val payslipId: String,
    val version: Long,
    val attempts: List<PayslipPaymentAttempt>,
    /** Missing only in records acquired before settlement was available. */
    val settlement: PayslipSettlement? = null,
) {
    override fun toString() = "PayslipPayments([redacted])"
}
