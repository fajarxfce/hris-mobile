package dev.fajar.starter.workforce.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.data.api.WorkforceApi
import dev.fajar.starter.workforce.data.datasources.ApiWorkforceRemoteDataSource
import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.data.repositories.RemoteWorkforceRepository
import dev.fajar.starter.workforce.domain.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class WorkforceRepositoryTest {
    @Test
    fun theApiPinsCompanyAndSessionScopeAndBoundsBothWireRepresentations() = runTest {
        val f = WorkforceDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    val body =
                        if (request.url.encodedPath.endsWith("/me/employments")) {
                            assertEquals(
                                "/api/v1/companies/${f.access.companyId}/workforce/me/employments",
                                request.url.encodedPath,
                            )
                            assertEquals("20", request.url.parameters["limit"])
                            assertEquals("E&00", request.url.parameters["after"])
                            Json.encodeToString(f.directory)
                        } else {
                            assertEquals(
                                "/api/v1/companies/${f.access.companyId}/workforce/employees/${f.employee.id}/calendar",
                                request.url.encodedPath,
                            )
                            assertEquals("2026-10-12", request.url.parameters["from"])
                            assertEquals("2026-10-18", request.url.parameters["until"])
                            Json.encodeToString(f.calendar)
                        }
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
            val repository =
                RemoteWorkforceRepository(ApiWorkforceRemoteDataSource(WorkforceApi(client)))
            assertIs<AppResult.Success<WorkforceDirectory>>(
                repository.selfEmployments(f.access, "E&00")
            )
            val calendar =
                assertIs<AppResult.Success<WorkCalendar>>(
                        repository.calendar(f.access, f.employee.id, f.period)
                    )
                    .value
            val work = assertIs<WorkDay.Work>(calendar.days.first())
            assertEquals(420L, work.plannedMinutes)
            assertEquals(CalendarWorkMode.ONSITE, work.shift.mode)
            assertIs<WorkDay.Off>(calendar.days[1])
        } finally {
            client.close()
        }
    }

    @Test
    fun validButOversizedJsonIsRejectedBeforeItCanBecomeADomainResult() = runTest {
        val f = WorkforceDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    val json =
                        if (request.url.encodedPath.endsWith("/me/employments"))
                            Json.encodeToString(f.directory)
                        else Json.encodeToString(f.calendar)
                    respond(
                        json.dropLast(1) + ",\"padding\":\"" + "x".repeat(70_000) + "\"}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repository =
                RemoteWorkforceRepository(ApiWorkforceRemoteDataSource(WorkforceApi(client)))
            assertIs<AppResult.Failed>(repository.selfEmployments(f.access, null))
            assertIs<AppResult.Failed>(repository.calendar(f.access, f.employee.id, f.period))
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedScopeOversizedDirectoriesAndNonProgressingCursorsFailInsideTheBoundary() =
        runTest {
            val f = WorkforceDataFixture()
            val original = f.directory
            val repository = RemoteWorkforceRepository(f)
            for (input in
                listOf(
                    original.copy(accountId = f.employee.id),
                    original.copy(companyId = f.employee.id),
                    original.copy(asOf = "2026-02-30"),
                    original.copy(timezone = "Unknown/Timezone"),
                    original.copy(employments = WorkforcePageDto(List(21) { f.employee })),
                    original.copy(employments = WorkforcePageDto(listOf(f.employee, f.employee))),
                    original.copy(employments = WorkforcePageDto(emptyList(), "E01")),
                    original.copy(employments = WorkforcePageDto(listOf(f.employee), "E01")),
                )) {
                f.directory = input
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(repository.selfEmployments(f.access, "E01"))
                        .failure
                        .kind,
                )
            }
        }

    @Test
    fun incompleteWeeksInconsistentIntervalsAndUnknownKindsAreRejected() = runTest {
        val f = WorkforceDataFixture()
        val original = f.calendar
        val repository = RemoteWorkforceRepository(f)
        for (input in
            listOf(
                original.copy(days = original.days.drop(1)),
                original.copy(days = original.days + original.days.last()),
                original.copy(scheduleVersion = -1),
                original.copy(days = List(7) { original.days.first() }),
                original.copy(
                    days = listOf(f.workDay.copy(plannedMinutes = 1)) + original.days.drop(1)
                ),
                original.copy(
                    days =
                        listOf(f.workDay.copy(endsAt = f.workDay.startsAt)) + original.days.drop(1)
                ),
                original.copy(
                    days = listOf(f.workDay.copy(kind = "UNKNOWN")) + original.days.drop(1)
                ),
                original.copy(
                    days = listOf(f.workDay.copy(origin = "HOLIDAY")) + original.days.drop(1)
                ),
            )) {
            f.calendar = input
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.calendar(f.access, f.employee.id, f.period))
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun cancellationPropagatesAndMappedCollectionsDoNotRetainMutableDtoLists() = runTest {
        val f = WorkforceDataFixture()
        val repository = RemoteWorkforceRepository(f)
        val raw = f.calendar.days.toMutableList()
        f.calendar = f.calendar.copy(days = raw)
        val result =
            assertIs<AppResult.Success<WorkCalendar>>(
                    repository.calendar(f.access, f.employee.id, f.period)
                )
                .value
        raw.clear()
        assertEquals(7, result.days.size)
        f.failure = CancellationException("Cancelled fixture")
        assertFailsWith<CancellationException> {
            repository.calendar(f.access, f.employee.id, f.period)
        }
    }
}
