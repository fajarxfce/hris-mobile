package dev.fajar.starter.workforce.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkCalendarSnapshotDto(
    val directory: WorkforceDirectoryDto,
    val employeeId: String?,
    val from: String,
    val after: String?,
    val calendar: WorkCalendarDto?,
    val permissions: Set<String>,
    val sessionExpiresAt: String,
    val fetchedAt: String,
    val expiresAt: String,
)
