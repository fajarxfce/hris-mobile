package dev.fajar.starter.dashboard.data.mappers

import dev.fajar.starter.dashboard.data.dto.DashboardDto
import dev.fajar.starter.dashboard.domain.entities.Activity
import dev.fajar.starter.dashboard.domain.entities.Dashboard

fun DashboardDto.toDashboard(): Dashboard {
    require(projects >= 0 && active in 0..projects && members >= 0) { "Invalid dashboard counts" }
    return Dashboard(
        projects,
        active,
        members,
        activity.map { Activity(it.id, it.title, it.detail, it.time) },
    )
}
