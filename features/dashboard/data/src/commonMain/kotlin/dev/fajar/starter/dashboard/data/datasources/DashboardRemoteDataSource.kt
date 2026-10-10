package dev.fajar.starter.dashboard.data.datasources

import dev.fajar.starter.dashboard.data.dto.DashboardDto

interface DashboardRemoteDataSource {
    suspend fun load(): DashboardDto
}
