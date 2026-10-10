package dev.fajar.starter.dashboard.domain.entities

data class Dashboard(
    val projects: Int,
    val active: Int,
    val members: Int,
    val activity: List<Activity>,
)
