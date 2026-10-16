package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** A tracked subset of the posted account balance, with its original business deadline. */
data class LeaveCarryBalance(
    val closingId: String,
    val awarded: LeaveDays,
    val available: LeaveDays,
    val reserved: LeaveDays,
    val consumed: LeaveDays,
    val expired: LeaveDays,
    val transferred: LeaveDays,
    val deducted: LeaveDays,
    val validThrough: LocalDate?,
    val expiresAt: Instant?,
    val timezone: String,
    val version: Long,
)
