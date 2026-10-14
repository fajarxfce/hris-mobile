package dev.fajar.starter.expenses.data.replica.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseCheckpointDto(
    val permissions: Set<String>,
    val syncSessionId: String,
    val sessionExpiresAt: String,
    val timezone: String,
    val phase: String,
    val cursor: String?,
    val refreshedAt: String?,
    val refreshAfter: String,
    val pollAt: String?,
    val schema: Int = 1,
)
