package dev.fajar.starter.expenses.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseClaimHeaderDto(
    val claimId: String,
    val claimVersion: Long,
    val employeeId: String,
    val acquisitionId: String,
    val sessionId: String,
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val timezone: String,
    val authorizationExpiresAt: String,
    val authorizationExpiryCode: String,
    val fetchedAt: String,
    val refreshAfter: String,
    val size: Int,
    val pendingHistoryAfter: Long?,
)
