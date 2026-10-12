package dev.fajar.starter.workforce.data.overtime.dto

import kotlinx.serialization.Serializable

@Serializable
data class OvertimeSnapshotDto<T>(
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val fetchedAt: String,
    val expiresAt: String,
    val value: T,
)
