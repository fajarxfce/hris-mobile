package dev.fajar.starter.people.data.datasources

import dev.fajar.starter.people.data.dto.*

interface PeopleRemoteDataSource {
    suspend fun directoryDepartments(
        sessionId: String,
        companyId: String,
        query: String,
    ): DirectoryDepartmentsDto

    suspend fun companyDirectory(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
        departmentId: String? = null,
    ): CompanyDirectoryDto

    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): SelfEmploymentDirectoryDto

    suspend fun employment(
        sessionId: String,
        companyId: String,
        employeeId: String,
        asOf: String,
    ): EmploymentDetailsDto

    suspend fun profile(sessionId: String, companyId: String, employeeId: String): PersonProfileDto
}
