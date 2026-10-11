package dev.fajar.starter.people.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.ApiResponseException
import dev.fajar.starter.people.data.api.PeopleApi
import dev.fajar.starter.people.data.datasources.ApiPeopleRemoteDataSource
import dev.fajar.starter.people.data.dto.*
import dev.fajar.starter.people.data.repositories.RemotePeopleRepository
import dev.fajar.starter.people.domain.entities.CompanyDirectory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class CompanyDirectoryRepositoryTest {
    @Test
    fun requestEscapesSearchAndCursorAndMapsTheMinimalCompanyProjection() = runTest {
        val source = PeopleDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(
                        "/api/v1/companies/${source.company}/employees/directory",
                        request.url.encodedPath,
                    )
                    assertEquals("20", request.url.parameters["limit"])
                    assertEquals("Sam & Co%", request.url.parameters["query"])
                    assertEquals("E&01", request.url.parameters["after"])
                    respond(
                        Json.encodeToString(source.companyPage),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val result =
                RemotePeopleRepository(ApiPeopleRemoteDataSource(PeopleApi(client)))
                    .companyDirectory("session", source.company, "Sam & Co%", "E&01")
            val entry = assertIs<AppResult.Success<CompanyDirectory>>(result).value.items.single()
            assertEquals("Sam", entry.name)
            assertEquals("Engineering", entry.department)
            assertFalse(entry.toString().contains("Sam"))
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidRowsDatesAndNonProgressingPagesFailInsideTheHttpBoundary() = runTest {
        val f = PeopleDataFixture()
        val original = f.companyPage
        val row = original.employees.items.single()
        val invalid =
            listOf(
                original.copy(asOf = "2026-02-30"),
                original.copy(timezone = "Invalid/Zone"),
                original.copy(employees = DirectoryEmployeePageDto(List(21) { row })),
                original.copy(employees = DirectoryEmployeePageDto(listOf(row, row))),
                original.copy(
                    employees = DirectoryEmployeePageDto(listOf(row.copy(id = "invalid")))
                ),
                original.copy(employees = DirectoryEmployeePageDto(listOf(row.copy(name = " ")))),
                original.copy(
                    employees =
                        DirectoryEmployeePageDto(listOf(row.copy(position = "x".repeat(201))))
                ),
                original.copy(employees = DirectoryEmployeePageDto(emptyList(), "E02")),
                original.copy(employees = DirectoryEmployeePageDto(listOf(row), "E03")),
            )
        val repository = RemotePeopleRepository(f)
        for (page in invalid) {
            f.companyPage = page
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        repository.companyDirectory("session", f.company, "", null)
                    )
                    .failure
                    .kind,
            )
        }
        f.companyPage = original
        assertIs<AppResult.Failed>(repository.companyDirectory("session", f.company, "", "E02"))
    }

    @Test
    fun exceptionMappingKeepsMfaClassificationAndPropagatesCancellation() = runTest {
        val f = PeopleDataFixture()
        val repository = RemotePeopleRepository(f)
        f.failure =
            ApiResponseException(
                403,
                """{"code":"mfa_required","title":"Private diagnostic"}""",
                null,
                null,
            )
        val denied =
            assertIs<AppResult.Failed>(repository.companyDirectory("session", f.company, "", null))
                .failure
        assertEquals("mfa_required", denied.code)
        assertFalse(denied.message.contains("Private diagnostic"))
        f.failure = CancellationException("Stopped")
        assertFailsWith<CancellationException> {
            repository.companyDirectory("session", f.company, "", null)
        }
    }
}
