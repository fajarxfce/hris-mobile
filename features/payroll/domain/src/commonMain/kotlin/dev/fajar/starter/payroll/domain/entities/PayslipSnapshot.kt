package dev.fajar.starter.payroll.domain.entities

import kotlin.time.Instant

data class PayslipSnapshot<T>(
    val access: PayrollAccess,
    val generation: String,
    val value: T,
    val fetchedAt: Instant,
    val expiresAt: Instant,
) {
    override fun toString() = "PayslipSnapshot([redacted])"
}
