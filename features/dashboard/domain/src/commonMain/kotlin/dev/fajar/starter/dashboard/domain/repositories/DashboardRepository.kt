package dev.fajar.starter.dashboard.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.dashboard.domain.entities.Dashboard

interface DashboardRepository {
    suspend fun load(): AppResult<Dashboard>
}
