package dev.fajar.starter.dashboard.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class DashboardDto(
    val projects: Int,
    val active: Int,
    val members: Int,
    val activity: List<ActivityDto>,
)
