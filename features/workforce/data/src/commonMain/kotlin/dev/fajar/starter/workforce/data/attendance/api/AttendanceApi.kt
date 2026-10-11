package dev.fajar.starter.workforce.data.attendance.api

import dev.fajar.starter.network.*
import dev.fajar.starter.workforce.data.attendance.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import org.koin.core.annotation.*

@Single
class AttendanceApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun days(
        sessionId: String,
        companyId: String,
        employeeId: String,
        from: String,
        until: String,
    ): List<AttendanceDayDto> =
        client.requestJson(
            "companies/$companyId/workforce/employees/$employeeId/attendance",
            maximumBytes = 2_097_152,
        ) {
            forSession(sessionId)
            parameter("from", from)
            parameter("until", until)
        }

    suspend fun window(
        sessionId: String,
        companyId: String,
        employeeId: String,
        operationId: String,
        request: AttendanceWindowRequest,
    ): AttendanceCaptureWindowDto =
        client.requestJson(
            "companies/$companyId/workforce/employees/$employeeId/attendance/windows",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun record(
        sessionId: String,
        companyId: String,
        employeeId: String,
        request: AttendanceCaptureDto,
    ): AttendanceReceiptDto =
        client.requestJson(
            "companies/$companyId/workforce/employees/$employeeId/attendance",
            HttpMethod.Post,
            maximumBytes = 4096,
        ) {
            forSession(sessionId)
            header("Idempotency-Key", request.id)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
}
