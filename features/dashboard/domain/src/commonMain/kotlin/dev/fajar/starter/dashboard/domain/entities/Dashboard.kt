package dev.fajar.starter.dashboard.domain.entities

data class Dashboard(
    val projects: Int,
    val active: Int,
    val members: Int,
    val activity: List<Activity>,
    val pendingChanges: Int = 0,
    val updatedAtEpochMillis: Long = 0,
    val sessionId: String = "",
    val hasMore: Boolean = false,
)
