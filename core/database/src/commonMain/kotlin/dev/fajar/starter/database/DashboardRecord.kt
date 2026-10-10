package dev.fajar.starter.database

import kotlinx.serialization.Serializable

@Serializable
data class DashboardRecord(
    val projects: Int,
    val active: Int,
    val members: Int,
    val activity: List<ActivityRecord>,
    val updatedAtEpochMillis: Long,
    val pendingChanges: Int = 0,
    val snapshot: String = "",
    val nextCursor: String? = null,
)
