package dev.fajar.starter.database.dto

/** A single query observes content, preferences, and outbox atomically. */
data class DashboardRow(
    val projects: Int,
    val active: Int,
    val members: Int,
    val updatedAtEpochMillis: Long,
    val snapshot: String,
    val nextCursor: String?,
    val pendingChanges: Int,
    val activityId: String?,
    val title: String?,
    val detail: String?,
    val time: String?,
    val saved: Boolean,
)
