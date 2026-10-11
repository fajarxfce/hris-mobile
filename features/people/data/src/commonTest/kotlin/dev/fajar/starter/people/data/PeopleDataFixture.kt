package dev.fajar.starter.people.data

import dev.fajar.starter.people.data.datasources.PeopleRemoteDataSource
import dev.fajar.starter.people.data.dto.*

class PeopleDataFixture : PeopleRemoteDataSource {
    val company = "11111111-1111-1111-1111-111111111111"
    val employee =
        EmploymentDto(
            "22222222-2222-2222-2222-222222222222",
            company,
            "E01",
            PersonSummaryDto(
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "Alex",
                "alex@example.test",
            ),
            EmploymentTermsDto("2025-01-01", "PERMANENT", "2025-01-01", status = "ACTIVE"),
            2,
            1,
        )
    val person =
        PersonProfileDto(
            employee.person.id,
            company,
            employee.person.accountId,
            "Alex",
            "1996-02-29",
            "ID",
            "alex@example.test",
            0,
        )
    var page =
        SelfEmploymentDirectoryDto(
            "2026-10-01",
            "Asia/Jakarta",
            EmploymentPageDto(listOf(employee)),
        )
    var details = EmploymentDetailsDto(page.asOf, employee)
    var failure: Exception? = null
    var companyPage =
        CompanyDirectoryDto(
            "2026-10-01",
            "Asia/Jakarta",
            DirectoryEmployeePageDto(
                listOf(DirectoryEmployeeDto(employee.id, "E02", "Sam", "Engineer", "Engineering"))
            ),
        )

    override suspend fun companyDirectory(
        sessionId: String,
        companyId: String,
        query: String,
        after: String?,
    ): CompanyDirectoryDto {
        failure?.let { throw it }
        return companyPage
    }

    override suspend fun selfEmployments(
        sessionId: String,
        companyId: String,
        after: String?,
    ): SelfEmploymentDirectoryDto {
        failure?.let { throw it }
        return page
    }

    override suspend fun employment(
        sessionId: String,
        companyId: String,
        employeeId: String,
        asOf: String,
    ) = details

    override suspend fun profile(sessionId: String, companyId: String, employeeId: String) = person
}
