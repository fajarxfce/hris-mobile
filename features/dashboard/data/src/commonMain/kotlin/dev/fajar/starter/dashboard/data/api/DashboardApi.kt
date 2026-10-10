package dev.fajar.starter.dashboard.data.api

import dev.fajar.starter.dashboard.data.dto.ActivityPreferenceRequest
import dev.fajar.starter.dashboard.data.dto.DashboardDto
import dev.fajar.starter.network.HttpClients
import dev.fajar.starter.network.forSession
import dev.fajar.starter.network.requestJson
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class DashboardApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun getDashboard(sessionId: String, cursor: String?): DashboardDto =
        client.requestJson("dashboard") {
            forSession(sessionId)
            cursor?.let { parameter("cursor", it) }
        }

    suspend fun putPreference(
        sessionId: String,
        operationId: String,
        request: ActivityPreferenceRequest,
    ) {
        client.requestJson<Unit>("dashboard/preferences", HttpMethod.Put) {
            forSession(sessionId)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
}
