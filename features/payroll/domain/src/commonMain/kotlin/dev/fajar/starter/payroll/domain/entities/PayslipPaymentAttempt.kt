package dev.fajar.starter.payroll.domain.entities

import kotlin.time.Instant

data class PayslipPaymentAttempt(
    val batchId: String,
    val itemId: String,
    val status: PayslipPaymentStatus,
    val createdAt: Instant,
    val releasedAt: Instant?,
    val resolvedAt: Instant?,
)
