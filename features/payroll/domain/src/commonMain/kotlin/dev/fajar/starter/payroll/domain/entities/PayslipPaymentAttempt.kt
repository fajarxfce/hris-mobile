package dev.fajar.starter.payroll.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlin.time.Instant

data class PayslipPaymentAttempt(
    val batchId: String,
    val itemId: String,
    val status: PayslipPaymentStatus,
    val createdAt: Instant,
    val releasedAt: Instant?,
    val resolvedAt: Instant?,
    /** Older local records do not identify the transferred amount or assessment revision. */
    val amount: DecimalAmount? = null,
    val assessmentRevision: Long? = null,
) {
    override fun toString() = "PayslipPaymentAttempt([redacted])"
}
