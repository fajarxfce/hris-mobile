package dev.fajar.starter.dashboard.data.datasources

import dev.fajar.starter.dashboard.data.dto.DashboardDto

interface DashboardRemoteDataSource {
    suspend fun load(sessionId: String, cursor: String? = null): DashboardDto

    suspend fun setSaved(
        sessionId: String,
        operationId: String,
        request: dev.fajar.starter.dashboard.data.dto.ActivityPreferenceRequest,
    )
}
