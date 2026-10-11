package dev.fajar.starter.leave.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeaveRequestSnapshotDto(
    val request: LeaveRequestDto,
    val historyAfter: Long?,
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val fetchedAt: String,
    val expiresAt: String,
)
