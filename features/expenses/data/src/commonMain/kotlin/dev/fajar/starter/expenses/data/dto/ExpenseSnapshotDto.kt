package dev.fajar.starter.expenses.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExpenseSnapshotDto<T>(
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val timezone: String,
    val fetchedAt: String,
    val expiresAt: String,
    val value: T,
)
