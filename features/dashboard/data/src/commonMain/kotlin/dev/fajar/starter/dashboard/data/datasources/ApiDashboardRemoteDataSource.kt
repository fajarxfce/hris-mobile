package dev.fajar.starter.dashboard.data.datasources

import dev.fajar.starter.dashboard.data.api.DashboardApi
import org.koin.core.annotation.Single

@Single
class ApiDashboardRemoteDataSource(private val api: DashboardApi) : DashboardRemoteDataSource {
    override suspend fun load() = api.getDashboard()
}
