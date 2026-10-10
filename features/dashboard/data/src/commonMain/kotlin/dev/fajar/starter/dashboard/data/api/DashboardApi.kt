package dev.fajar.starter.dashboard.data.api

import dev.fajar.starter.dashboard.data.dto.DashboardDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import org.koin.core.annotation.Single

@Single
class DashboardApi(private val client: HttpClient) {
    suspend fun getDashboard(): DashboardDto = client.get("dashboard").body()
}
