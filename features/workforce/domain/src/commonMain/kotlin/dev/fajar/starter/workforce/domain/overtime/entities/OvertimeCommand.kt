package dev.fajar.starter.workforce.domain.overtime.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Confirmed input is immutable; session credentials never enter durable command storage. */
data class OvertimeCommand(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val requestId: String,
    val employeeId: String,
    val employeeNumber: String,
    val employmentAfter: String?,
    val workDate: LocalDate,
    val timezone: String,
    val payload: OvertimeCommandPayload,
    val reason: String,
    val createdAt: Instant,
) {
    override fun toString() = "OvertimeCommand([redacted])"
}
