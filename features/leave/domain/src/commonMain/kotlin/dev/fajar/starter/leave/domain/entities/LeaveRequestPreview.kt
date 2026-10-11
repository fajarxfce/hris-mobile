package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Server-calculated working dates and proposed balances; no entitlement is reserved. */
data class LeaveRequestPreview(
    val employeeId: String,
    val evaluatedAt: Instant,
    val timezone: String,
    val policy: LeaveRequestPolicy,
    val days: List<LeaveDay>,
    val charged: LeaveDays,
    val excludedDates: List<LocalDate>,
    val balances: List<LeavePreviewBalance>,
)
