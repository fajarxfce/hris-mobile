package dev.fajar.starter.people.data.api

import dev.fajar.starter.network.*
import dev.fajar.starter.people.data.dto.*
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import org.koin.core.annotation.*

@Single
class PeopleApi(@Named(HttpClients.Authenticated) private val client: HttpClient) {
    suspend fun directoryDepartments(
        sessionId: String,
        companyId: String,
        query: String,
    ): DirectoryDepartmentsDto =
        client.requestJson(
            "companies/$companyId/employees/directory/departments",
            maximumBytes = 65_536,
        ) {
            forSession(sessionId)
            parameter("limit", 50)
            parameter("query", query)
        }

    suspend fun companyDirectory(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
        departmentId: String? = null,
    ): CompanyDirectoryDto =
        client.requestJson("companies/$companyId/employees/directory", maximumBytes = 131_072) {
            forSession(sessionId)
            parameter("limit", 20)
            parameter("query", query)
            after?.let { parameter("after", it) }
            departmentId?.let { parameter("departmentId", it) }
        }

    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): SelfEmploymentDirectoryDto =
        client.requestJson("companies/$companyId/me/employments", maximumBytes = 131_072) {
            forSession(sessionId)
            parameter("limit", 20)
            after?.let { parameter("after", it) }
        }

    suspend fun employment(
        sessionId: String,
        companyId: String,
        employeeId: String,
        asOf: String,
    ): EmploymentDetailsDto =
        client.requestJson(
            "companies/$companyId/employees/$employeeId/employment",
            maximumBytes = 16_384,
        ) {
            forSession(sessionId)
            parameter("asOf", asOf)
        }

    suspend fun profile(
        sessionId: String,
        companyId: String,
        employeeId: String,
    ): PersonProfileDto =
        client.requestJson(
            "companies/$companyId/employees/$employeeId/profile",
            maximumBytes = 8192,
        ) {
            forSession(sessionId)
        }
}
