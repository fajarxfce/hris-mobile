package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.datasources.ApiLeaveDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.RemoteLeaveRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.network.RequestSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class LeaveRepositoryTest {
    @Test
    fun exactHalfDaysRejectMalformedDecimalPrecisionAndOverflow() {
        assertEquals(LeaveDays(21), "10.5".toLeaveDays())
        assertEquals(LeaveDays(0), "0".toLeaveDays())
        assertEquals(LeaveDays(Int.MAX_VALUE), "1073741823.5".toLeaveDays())
        assertEquals("1.5", LeaveDays(3).toWireDays())
        for (input in
            listOf("1.0", "1.25", "-1", "NaN", "1e2", "01", "1073741824", "9999999999999", " 1")) {
            assertFailsWith<IllegalArgumentException>(input) { input.toLeaveDays() }
        }
    }

    @Test
    fun apiUsesIndependentLeavePathsAndEncodesFiltersWithFiniteWireBounds() = runTest {
        val f = LeaveDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals("20", request.url.parameters["limit"])
                    val body =
                        when {
                            request.url.encodedPath.endsWith("/me/employments") -> {
                                assertEquals(
                                    "/api/v1/companies/${f.access.companyId}/leave/me/employments",
                                    request.url.encodedPath,
                                )
                                assertEquals("E&00", request.url.parameters["after"])
                                Json.encodeToString(f.directory)
                            }
                            request.url.encodedPath.endsWith("/balances") -> {
                                assertEquals(
                                    "/api/v1/companies/${f.access.companyId}/leave/employees/${f.employee.id}/balances",
                                    request.url.encodedPath,
                                )
                                assertEquals("2026", request.url.parameters["year"])
                                Json.encodeToString(f.balances)
                            }
                            else -> {
                                assertEquals(
                                    "/api/v1/companies/${f.access.companyId}/leave/requests",
                                    request.url.encodedPath,
                                )
                                assertEquals(f.employee.id, request.url.parameters["employeeId"])
                                assertEquals("PENDING", request.url.parameters["status"])
                                Json.encodeToString(f.requests)
                            }
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
            val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
            assertIs<AppResult.Success<LeaveDirectory>>(
                repository.selfEmployments(f.access, "E&00")
            )
            assertEquals(
                LeaveDays(21),
                assertIs<AppResult.Success<LeavePage<LeaveBalance>>>(
                        repository.balances(f.access, f.employee.id, 2026, null)
                    )
                    .value
                    .items
                    .single()
                    .available,
            )
            assertEquals(
                LeaveDays(3),
                assertIs<AppResult.Success<LeavePage<LeaveRequestSummary>>>(
                        repository.requests(f.access, f.employee.id, LeaveStatus.PENDING, null)
                    )
                    .value
                    .items
                    .single()
                    .charged,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun wrongOwnerDuplicatesAndNonProgressingDirectoryCursorsFailInsideSafeBoundary() = runTest {
        val f = LeaveDataFixture()
        val original = f.directory
        val repository = RemoteLeaveRepository(f)
        for (input in
            listOf(
                original.copy(accountId = f.employee.id),
                original.copy(companyId = f.employee.id),
                original.copy(asOf = "2026-02-30"),
                original.copy(timezone = "Unknown/Zone"),
                original.copy(employments = LeavePageDto(List(21) { f.employee })),
                original.copy(employments = LeavePageDto(emptyList(), "E01")),
                original.copy(employments = LeavePageDto(listOf(f.employee), "E01")),
            )) {
            f.directory = input
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.selfEmployments(f.access, "E01")).failure.kind,
            )
        }
    }

    @Test
    fun balanceAndRequestMappingRejectWrongScopeInvalidPagesAndMalformedAmounts() = runTest {
        val f = LeaveDataFixture()
        val repository = RemoteLeaveRepository(f)
        val original = f.balances
        val malformedAmount =
            f.balance.copy(balance = f.balance.balance.copy(availableDays = "1.1"))
        val missingAccount = f.balance.copy(balance = f.balance.balance.copy(accountId = null))
        for (input in
            listOf(
                original.copy(employee = LeaveEmployeeReferenceDto(f.access.companyId)),
                original.copy(year = 2025),
                original.copy(balances = LeavePageDto(listOf(f.balance, f.balance))),
                original.copy(balances = LeavePageDto(listOf(f.balance), "ANNUAL")),
                original.copy(balances = LeavePageDto(listOf(malformedAmount))),
                original.copy(balances = LeavePageDto(listOf(missingAccount))),
            )) {
            f.balances = input
            assertIs<AppResult.Failed>(repository.balances(f.access, f.employee.id, 2026, null))
        }
        for (row in
            listOf(
                f.request.copy(employeeId = f.access.companyId),
                f.request.copy(status = "APPROVED"),
                f.request.copy(until = "2026-10-14"),
                f.request.copy(chargedDays = "3"),
                f.request.copy(submittedAt = "not an instant"),
                f.request.copy(version = -1),
            )) {
            f.requests = LeavePageDto(listOf(row))
            assertIs<AppResult.Failed>(
                repository.requests(f.access, f.employee.id, LeaveStatus.PENDING, null)
            )
        }
        f.requests = LeavePageDto(listOf(f.request), f.request.id)
        assertIs<AppResult.Failed>(repository.requests(f.access, f.employee.id, null, f.request.id))
    }

    @Test
    fun oversizedJsonIsRejectedBeforeBecomingADomainResult() = runTest {
        val f = LeaveDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        Json.encodeToString(f.balances).dropLast(1) +
                            ",\"padding\":\"" +
                            "x".repeat(70_000) +
                            "\"}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
            assertIs<AppResult.Failed>(repository.balances(f.access, f.employee.id, 2026, null))
        } finally {
            client.close()
        }
    }

    @Test
    fun cancellationPropagatesAndMappedValuesDoNotRetainMutableDtos() = runTest {
        val f = LeaveDataFixture()
        val raw = f.balances.balances.items.toMutableList()
        f.balances = f.balances.copy(balances = LeavePageDto(raw))
        val repository = RemoteLeaveRepository(f)
        val mapped =
            assertIs<AppResult.Success<LeavePage<LeaveBalance>>>(
                    repository.balances(f.access, f.employee.id, 2026, null)
                )
                .value
        raw.clear()
        assertEquals(1, mapped.items.size)
        f.failure = CancellationException("Cancelled fixture")
        assertFailsWith<CancellationException> {
            repository.requests(f.access, f.employee.id, null, null)
        }
    }
}
