package dev.fajar.starter.people.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.people.domain.entities.*
import kotlinx.datetime.LocalDate

interface PeopleRepository {
    suspend fun companyDirectory(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ): AppResult<CompanyDirectory>

    suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): AppResult<SelfEmploymentDirectory>

    suspend fun employment(
        sessionId: String,
        companyId: String,
        employeeId: String,
        asOf: LocalDate,
    ): AppResult<EmploymentDetails>

    suspend fun profile(
        sessionId: String,
        companyId: String,
        employeeId: String,
    ): AppResult<PersonProfile>
}
