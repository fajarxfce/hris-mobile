package dev.fajar.starter.leave.data.api

import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.network.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class LeaveApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun submit(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: LeaveSubmissionRequest,
    ): LeaveActionReceiptDto =
        client.requestJson("companies/$companyId/leave/requests", maximumBytes = 4_096) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun types(
        sessionId: String,
        companyId: String,
        asOf: String,
        after: String?,
    ): LeavePageDto<LeaveTypeDto> =
        client.requestJson("companies/$companyId/leave/types", maximumBytes = 65_536) {
            forSession(sessionId)
            parameter("asOf", asOf)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun preview(
        sessionId: String,
        companyId: String,
        request: LeavePreviewRequest,
    ): LeaveRequestPreviewDto =
        client.requestJson(
            "companies/$companyId/leave/me/requests/preview",
            maximumBytes = 262_144,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun act(
        sessionId: String,
        companyId: String,
        requestId: String,
        operationId: String,
        action: String,
        request: LeaveActionRequest,
    ): LeaveActionReceiptDto =
        client.requestJson(
            "companies/$companyId/leave/requests/$requestId/$action",
            maximumBytes = 4_096,
        ) {
            forSession(sessionId)
            method = HttpMethod.Post
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

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
