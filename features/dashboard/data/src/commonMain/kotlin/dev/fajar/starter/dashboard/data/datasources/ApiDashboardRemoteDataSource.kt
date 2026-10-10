package dev.fajar.starter.dashboard.data.datasources

import dev.fajar.starter.dashboard.data.api.DashboardApi
import org.koin.core.annotation.Single

@Single
class ApiDashboardRemoteDataSource(private val api: DashboardApi) : DashboardRemoteDataSource {
    override suspend fun load(sessionId: String, cursor: String?) =
        api.getDashboard(sessionId, cursor)

    override suspend fun setSaved(
        sessionId: String,
        operationId: String,
        request: dev.fajar.starter.dashboard.data.dto.ActivityPreferenceRequest,
    ) = api.putPreference(sessionId, operationId, request)
}
