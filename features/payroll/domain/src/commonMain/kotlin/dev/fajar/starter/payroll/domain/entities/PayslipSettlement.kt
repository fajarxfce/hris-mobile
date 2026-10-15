package dev.fajar.starter.payroll.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount

/** Server-confirmed balances refer to their own assessment revision, independently of the slip. */
data class PayslipSettlement(
    val assessmentRevision: Long,
    val entitlement: DecimalAmount,
    val confirmedPaid: DecimalAmount,
    val confirmedRecovered: DecimalAmount,
    val payable: DecimalAmount,
    val recoverable: DecimalAmount,
    val currency: String,
) {
    override fun toString() = "PayslipSettlement([redacted])"
}
