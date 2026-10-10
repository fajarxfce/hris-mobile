package dev.fajar.starter.people.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.ApiResponseException
import dev.fajar.starter.people.data.api.PeopleApi
import dev.fajar.starter.people.data.datasources.ApiPeopleRemoteDataSource
import dev.fajar.starter.people.data.dto.*
import dev.fajar.starter.people.data.repositories.RemotePeopleRepository
import dev.fajar.starter.people.domain.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

class PeopleRepositoryTest {
    @Test
    fun theApiUsesBoundedSelfDiscoveryAndKeepsRequestParametersSeparateFromPaths() = runTest {
        val source = PeopleDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(
                        "/api/v1/companies/${source.company}/me/employments",
                        request.url.encodedPath,
                    )
                    assertEquals("20", request.url.parameters["limit"])
                    assertEquals("E&02", request.url.parameters["after"])
                    respond(
                        Json.encodeToString(source.page),
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
                    .selfEmployments("session", source.company, "E&02")
            val directory = assertIs<AppResult.Success<SelfEmploymentDirectory>>(result).value
            assertEquals(LocalDate(2026, 10, 1), directory.asOf)
            assertEquals(EmploymentStatus.Active, directory.items.single().status)
            assertEquals(2L, directory.items.single().version)
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedScopeDuplicateRecordsAndNonProgressingPagesFailInsideTheBoundary() = runTest {
        val source = PeopleDataFixture()
        val repository = RemotePeopleRepository(source)
        val initial = source.page
        for (page in
            listOf(
                initial.copy(asOf = "2026-02-30"),
                initial.copy(employments = EmploymentPageDto(List(21) { source.employee })),
                initial.copy(
                    employments = EmploymentPageDto(listOf(source.employee, source.employee))
                ),
                initial.copy(
                    employments =
                        EmploymentPageDto(
                            listOf(source.employee.copy(companyId = source.employee.id))
                        )
                ),
                initial.copy(employments = EmploymentPageDto(emptyList(), "E01")),
                initial.copy(employments = EmploymentPageDto(listOf(source.employee), "E01")),
            )) {
            source.page = page
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        repository.selfEmployments("session", source.company, "E01")
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun assignmentIdsAndProfileDatesAreValidatedWhileMapping() = runTest {
        val source = PeopleDataFixture()
        val repository = RemotePeopleRepository(source)
        val date = LocalDate(2026, 10, 1)
        assertIs<AppResult.Success<EmploymentDetails>>(
            repository.employment("session", source.company, source.employee.id, date)
        )
        source.details =
            source.details.copy(
                branch = EmploymentAssignmentDto(source.employee.id, "HQ", "Office", true)
            )
        assertIs<AppResult.Failed>(
            repository.employment("session", source.company, source.employee.id, date)
        )
        val person =
            assertIs<AppResult.Success<PersonProfile>>(
                    repository.profile("session", source.company, source.employee.id)
                )
                .value
        assertEquals(LocalDate(1996, 2, 29), person.birthDate)
        assertFalse(person.toString().contains("Alex"))
    }

    @Test
    fun technicalFailuresAreTranslatedAndCancellationIsPreserved() = runTest {
        val source = PeopleDataFixture()
        val repository = RemotePeopleRepository(source)
        source.failure =
            ApiResponseException(
                403,
                """{"code":"mfa_required","title":"Private diagnostic"}""",
                null,
                null,
            )
        val denied =
            assertIs<AppResult.Failed>(repository.selfEmployments("session", source.company, null))
                .failure
        assertEquals("mfa_required", denied.code)
        assertFalse(denied.message.contains("Private diagnostic"))
        source.failure = CancellationException("Stopped")
        assertFailsWith<CancellationException> {
            repository.selfEmployments("session", source.company, null)
        }
    }
}
