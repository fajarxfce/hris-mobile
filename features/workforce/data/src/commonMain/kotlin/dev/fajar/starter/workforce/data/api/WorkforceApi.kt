package dev.fajar.starter.workforce.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.workforce.data.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import org.koin.core.annotation.*

@Single
class WorkforceApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): WorkforceDirectoryDto =
        client.requestJson("companies/$companyId/workforce/me/employments", maximumBytes = 32_768) {
            forSession(sessionId)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun calendar(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ): WorkCalendarDto =
        client.requestJson(
            "companies/$companyId/workforce/employees/$employeeId/calendar",
            maximumBytes = 65_536,
        ) {
            forSession(sessionId)
            parameter("from", from)
            parameter("until", until)
        }
}
