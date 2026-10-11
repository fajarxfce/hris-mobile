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
    fun maximumDepartmentPageAcceptsValidMultibyteNamesWithinTheTransportBound() = runTest {
        val f = PeopleDataFixture()
        val page =
            DirectoryDepartmentsDto(
                (1..50).map {
                    DirectoryDepartmentDto(
                        "82000000-0000-4000-8000-" + it.toString().padStart(12, '0'),
                        "部".repeat(200),
                    )
                },
                true,
            )
        val body = Json.encodeToString(page)
        assertTrue(body.encodeToByteArray().size > 32_768)
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            assertIs<AppResult.Success<*>>(
                RemotePeopleRepository(ApiPeopleRemoteDataSource(PeopleApi(client)))
                    .directoryDepartments("session", f.company, "")
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun departmentLookupEncodesSearchAndRejectsUnboundedOrInvalidOptions() = runTest {
        val f = PeopleDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(
                        "/api/v1/companies/${f.company}/employees/directory/departments",
                        request.url.encodedPath,
                    )
                    assertEquals("50", request.url.parameters["limit"])
                    assertEquals("R&D %", request.url.parameters["query"])
                    respond(
                        Json.encodeToString(f.departments),
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
                    .directoryDepartments("session", f.company, "R&D %")
            assertEquals(
                "Engineering",
                assertIs<AppResult.Success<*>>(result).let {
                    (it.value as dev.fajar.starter.people.domain.entities.DirectoryDepartments)
                        .items
                        .single()
                        .name
                },
            )
        } finally {
            client.close()
        }
        val original = f.departments
        val row = original.items.single()
        val repository = RemotePeopleRepository(f)
        for (invalid in
            listOf(
                original.copy(hasMore = true),
                original.copy(items = List(51) { row }),
                original.copy(items = listOf(row, row)),
                original.copy(items = listOf(row.copy(id = "invalid"))),
                original.copy(items = listOf(row.copy(name = " "))),
            )) {
            f.departments = invalid
            assertIs<AppResult.Failed>(repository.directoryDepartments("session", f.company, ""))
        }
        f.departments = original
        f.failure = CancellationException("Stopped")
        assertFailsWith<CancellationException> {
            repository.directoryDepartments("session", f.company, "")
        }
    }

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
                    assertEquals(
                        source.departments.items.single().id,
                        request.url.parameters["departmentId"],
                    )
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
                    .companyDirectory(
                        "session",
                        source.company,
                        "Sam & Co%",
                        "E&01",
                        source.departments.items.single().id,
                    )
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
