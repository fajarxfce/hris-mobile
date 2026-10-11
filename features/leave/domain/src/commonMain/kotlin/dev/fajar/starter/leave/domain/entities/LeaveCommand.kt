package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant

/** Immutable intent, retained for the same account and API audience across session replacement. */
data class LeaveCommand(
    val id: String,
    val accountId: String,
    val audience: String,
    val companyId: String,
    val requestId: String,
    val expectedVersion: Long,
    val action: LeaveRequestAction,
    val reason: String,
    val createdAt: Instant,
) {
    override fun toString() = "LeaveCommand([redacted])"
}
