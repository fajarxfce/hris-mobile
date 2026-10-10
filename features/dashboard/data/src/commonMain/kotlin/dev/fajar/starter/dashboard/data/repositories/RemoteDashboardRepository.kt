package dev.fajar.starter.dashboard.data.repositories

import dev.fajar.starter.dashboard.data.datasources.DashboardRemoteDataSource
import dev.fajar.starter.dashboard.data.mappers.toDashboard
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.network.safeApiCall
import org.koin.core.annotation.Single

@Single
class RemoteDashboardRepository(private val remote: DashboardRemoteDataSource) :
    DashboardRepository {
    override suspend fun load() = safeApiCall { remote.load().toDashboard() }
}
