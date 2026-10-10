package dev.fajar.starter.dashboard.data.mappers

import dev.fajar.starter.dashboard.data.dto.DashboardDto
import dev.fajar.starter.dashboard.domain.entities.*
import dev.fajar.starter.database.*

fun DashboardDto.toRecord(updatedAt: Long): DashboardRecord {
    require(projects >= 0 && active in 0..projects && members >= 0) { "Invalid dashboard counts" }
    require(
        activity.all { it.id.isNotBlank() } &&
            activity.map { it.id }.distinct().size == activity.size
    ) {
        "Invalid activity identifiers"
    }
    return DashboardRecord(
        projects,
        active,
        members,
        activity.map { ActivityRecord(it.id, it.title, it.detail, it.time) },
        updatedAt,
        nextCursor = nextCursor,
    )
}

fun DashboardRecord.toDashboard() =
    Dashboard(
        projects,
        active,
        members,
        activity.map { Activity(it.id, it.title, it.detail, it.time, it.saved) },
        pendingChanges,
        updatedAtEpochMillis,
        hasMore = nextCursor != null,
    )
