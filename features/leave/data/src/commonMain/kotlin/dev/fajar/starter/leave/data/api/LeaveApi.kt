package dev.fajar.starter.leave.data.api

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import org.koin.core.annotation.*

@Single
class LeaveApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun details(
        sessionId: String,
        companyId: String,
        id: String,
        historyAfter: Long?,
    ): LeaveRequestDto =
        client.requestJson("companies/$companyId/leave/me/requests/$id", maximumBytes = 1_048_576) {
            forSession(sessionId)
            parameter("historyLimit", 10)
            historyAfter?.let { parameter("historyAfter", it) }
        }

    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): LeaveDirectoryDto =
        client.requestJson("companies/$companyId/leave/me/employments", maximumBytes = 32_768) {
            forSession(sessionId)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun balances(
        sessionId: String,
        companyId: String,
        employeeId: String,
        year: Int,
        after: String?,
    ): LeaveBalancesDto =
        client.requestJson(
            "companies/$companyId/leave/employees/$employeeId/balances",
            maximumBytes = 65_536,
        ) {
            forSession(sessionId)
            parameter("year", year)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun requests(
        sessionId: String,
        companyId: String,
        employeeId: String,
        status: String?,
        after: String?,
    ): LeavePageDto<LeaveRequestSummaryDto> =
        client.requestJson("companies/$companyId/leave/requests", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("employeeId", employeeId)
            parameter("limit", 20)
            status?.let { parameter("status", it) }
            after?.let { parameter("after", it) }
        }
}
