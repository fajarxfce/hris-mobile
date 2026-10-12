package dev.fajar.starter.workforce.data.overtime.api

import dev.fajar.starter.network.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import org.koin.core.annotation.*

@Single
class OvertimeApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
        status: String?,
        after: String?,
    ): OvertimePageDto<OvertimeRequestDto> =
        client.requestJson("companies/$companyId/workforce/overtime", maximumBytes = 262_144) {
            forSession(sessionId)
            parameter("employeeId", employeeId)
            parameter("from", from)
            parameter("until", until)
            parameter("limit", 20)
            status?.let { parameter("status", it) }
            after?.let { parameter("after", it) }
        }

    suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): OvertimeDetailsDto =
        client.requestJson("companies/$companyId/workforce/overtime/$id", maximumBytes = 262_144) {
            forSession(sessionId)
            parameter("historyLimit", 10)
            historyAfter?.let { parameter("historyAfter", it) }
        }
}
