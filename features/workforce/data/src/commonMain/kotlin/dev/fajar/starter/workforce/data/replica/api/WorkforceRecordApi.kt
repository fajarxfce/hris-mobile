package dev.fajar.starter.workforce.data.replica.api

import dev.fajar.starter.network.*
import dev.fajar.starter.workforce.data.replica.dto.*
import io.ktor.client.HttpClient
import org.koin.core.annotation.*

@Single
class WorkforceRecordApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun attendance(sessionId: String, companyId: String, id: String): AttendanceRecordDto =
        client.requestJson(
            "companies/$companyId/workforce/attendance/days/$id",
            maximumBytes = 131_072,
        ) {
            forSession(sessionId)
        }

    suspend fun schedule(sessionId: String, companyId: String, id: String): WorkScheduleRecordDto =
        client.requestJson(
            "companies/$companyId/workforce/me/schedules/$id",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
        }

    suspend fun roster(sessionId: String, companyId: String, id: String): RosterRecordDto =
        client.requestJson("companies/$companyId/workforce/me/roster/$id", maximumBytes = 8_192) {
            forSession(sessionId)
        }

    suspend fun holiday(sessionId: String, companyId: String, id: String): WorkHolidayRecordDto =
        client.requestJson("companies/$companyId/workforce/me/holidays/$id", maximumBytes = 4_096) {
            forSession(sessionId)
        }
}
