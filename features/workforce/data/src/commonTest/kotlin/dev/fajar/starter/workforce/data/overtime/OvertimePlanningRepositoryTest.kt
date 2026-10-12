package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.data.dto.WorkDayDto
import dev.fajar.starter.workforce.data.overtime.api.OvertimeApi
import dev.fajar.starter.workforce.data.overtime.datasources.ApiOvertimeDataSource
import dev.fajar.starter.workforce.data.overtime.repositories.RemoteOvertimeRepository
import dev.fajar.starter.workforce.domain.entities.WorkDay
import dev.fajar.starter.workforce.domain.overtime.entities.*
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

class OvertimePlanningRepositoryTest {
    @Test
    fun reviewUsesTheOwnedScopedEndpointAndRetainsItsScheduleRevision() = runTest {
        val f = OvertimeDataFixture()
        val selection =
            OvertimePlanningSelection(f.base.employee.id, LocalDate.parse(f.request.workDate))
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/workforce/overtime/planning/employees/${selection.employeeId}",
                        request.url.encodedPath,
                    )
                    assertEquals(setOf("workDate"), request.url.parameters.names())
                    assertEquals(selection.workDate.toString(), request.url.parameters["workDate"])
                    respond(
                        Json.encodeToString(
                            f.review.copy(employee = f.review.employee.copy(version = 4))
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val result =
                assertIs<AppResult.Success<OvertimePlanningReview>>(
                        RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
                            .review(f.access, selection)
                    )
                    .value
            assertEquals(4L, result.employee.version)
            assertEquals(f.review.revision, result.revision)
            assertEquals(f.review.timezone, result.timezone)
            assertIs<WorkDay.Work>(result.schedule)
            assertEquals(selection.workDate, result.schedule.date)
        } finally {
            client.close()
        }
    }

    @Test
    fun inconsistentEmployeeDatesScheduleAndRevisionFailInsideTheDataBoundary() = runTest {
        val f = OvertimeDataFixture()
        val repository = RemoteOvertimeRepository(f)
        val selection =
            OvertimePlanningSelection(f.base.employee.id, LocalDate.parse(f.request.workDate))
        val original = f.review
        for (value in
            listOf(
                original.copy(
                    employee = original.employee.copy(id = "99999999-9999-9999-9999-999999999999")
                ),
                original.copy(employee = original.employee.copy(version = -1)),
                original.copy(workDate = "2026-10-13"),
                original.copy(companyDate = "2026-02-30"),
                original.copy(revision = "x".repeat(64)),
                original.copy(timezone = "Unknown/Zone"),
                original.copy(timezone = "America/New_York"),
                original.copy(schedule = WorkDayDto(original.workDate, "UNASSIGNED")),
            )) {
            f.review = value
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.review(f.access, selection)).failure.kind,
            )
        }
    }

    @Test
    fun cancellationPropagatesAndTechnicalTextStaysOutOfFailures() = runTest {
        val f = OvertimeDataFixture()
        val selection =
            OvertimePlanningSelection(f.base.employee.id, LocalDate.parse(f.request.workDate))
        val repository = RemoteOvertimeRepository(f)
        f.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { repository.review(f.access, selection) }
        f.failure = IllegalStateException("private request metadata")
        val failure = assertIs<AppResult.Failed>(repository.review(f.access, selection)).failure
        assertEquals(FailureKind.Unexpected, failure.kind)
        assertFalse(failure.message.contains("private request metadata"))
    }

    @Test
    fun currentServerDenialRetainsItsLocalizableCode() = runTest {
        val f = OvertimeDataFixture()
        val selection =
            OvertimePlanningSelection(f.base.employee.id, LocalDate.parse(f.request.workDate))
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"type":"about:blank","title":"private details","status":403,"code":"mfa_required"}""",
                        HttpStatusCode.Forbidden,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val failure =
                assertIs<AppResult.Failed>(
                        RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
                            .review(f.access, selection)
                    )
                    .failure
            assertEquals("mfa_required", failure.code)
            assertEquals(FailureKind.AccessDenied, failure.kind)
            assertFalse(failure.message.contains("private details"))
        } finally {
            client.close()
        }
    }

    @Test
    fun reviewBodyIsBoundedBeforeDecoding() = runTest {
        val f = OvertimeDataFixture()
        val selection =
            OvertimePlanningSelection(f.base.employee.id, LocalDate.parse(f.request.workDate))
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        "{\"padding\":\"" + "x".repeat(33_000) + "\"}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            assertIs<AppResult.Failed>(
                RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
                    .review(f.access, selection)
            )
        } finally {
            client.close()
        }
    }
}
