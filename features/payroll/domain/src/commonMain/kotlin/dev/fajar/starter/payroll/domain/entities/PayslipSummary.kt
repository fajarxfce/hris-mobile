package dev.fajar.starter.payroll.domain.entities

import dev.fajar.starter.common.decimal.DecimalAmount
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

data class PayslipSummary(
    val id: String,
    val employeeId: String,
    val employeeNumber: String,
    val employeeName: String,
    val month: YearMonth,
    val plannedPaymentDate: LocalDate,
    val currency: String,
    val taxableGross: DecimalAmount,
    val withheld: DecimalAmount,
    val takeHome: DecimalAmount,
    val publishedAt: Instant,
    val version: Long = 0,
) {
    override fun toString() = "PayslipSummary([redacted])"
}
