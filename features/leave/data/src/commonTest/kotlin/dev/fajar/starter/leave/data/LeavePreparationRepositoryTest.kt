package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.datasources.ApiLeavePreparationDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.toTypes
import dev.fajar.starter.leave.data.repositories.RemoteLeavePreparationRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

class LeavePreparationRepositoryTest {
    @Test
    fun previewAndCatalogUseOwnedBoundedTransportWithoutCommandHeaders() = runTest {
        val f = LeavePreparationDataFixture()
        var calls = 0
        val client =
            createHttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(f.base.access.sessionId, request.attributes[RequestSession])
                    assertNull(request.headers["Idempotency-Key"])
                    if (request.method == HttpMethod.Get) {
                        assertEquals(
                            "/api/v1/companies/${f.base.access.companyId}/leave/types",
                            request.url.encodedPath,
                        )
                        assertEquals("20", request.url.parameters["limit"])
                        assertEquals("2026-10-15", request.url.parameters["asOf"])
                        assertEquals("A", request.url.parameters["after"])
                        respond(
                            Json.encodeToString(f.page),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals(
                            "/api/v1/companies/${f.base.access.companyId}/leave/me/requests/preview",
                            request.url.encodedPath,
                        )
                        assertEquals(
                            Json.parseToJsonElement(
                                """{"employeeId":"${f.base.employee.id}","typeId":"${f.type.id}","days":[{"workDate":"2026-10-15","portion":"FULL"},{"workDate":"2026-10-16","portion":"FIRST_HALF"},{"workDate":"2026-10-17","portion":"FULL"}]}"""
                            ),
                            Json.parseToJsonElement((request.body as TextContent).text),
                        )
                        respond(
                            Json.encodeToString(f.value),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository =
                RemoteLeavePreparationRepository(ApiLeavePreparationDataSource(LeaveApi(client)))
            val types =
                assertIs<AppResult.Success<LeavePage<LeaveTypeOption>>>(
                        repository.types(
                            f.base.access,
                            LeaveTypeSelection(LocalDate(2026, 10, 15), "A"),
                        )
                    )
                    .value
            assertEquals(f.type.id, types.items.single().id)
            val preview =
                assertIs<AppResult.Success<LeaveRequestPreview>>(
                        repository.preview(f.base.access, f.selection)
                    )
                    .value
            assertEquals(LeaveDays(3), preview.charged)
            assertEquals(LeaveDays(18), preview.balances.single().remaining)
            assertEquals(listOf(LocalDate(2026, 10, 17)), preview.excludedDates)
            assertEquals(2, preview.days.size)
            assertEquals(2, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun incompleteOrContradictoryDateAndBalanceFactsCannotBecomeAReview() = runTest {
        val f = LeavePreparationDataFixture()
        val original = f.value
        val balance = original.balances.single()
        val repository = RemoteLeavePreparationRepository(f)
        for (invalid in
            listOf(
                original.copy(employeeId = f.type.id),
                original.copy(policy = original.policy.copy(typeId = f.base.employee.id)),
                original.copy(timezone = "Unknown/Zone"),
                original.copy(evaluatedAt = "not-a-timestamp"),
                original.copy(days = emptyList()),
                original.copy(days = original.days.reversed()),
                original.copy(days = original.days + original.days.first()),
                original.copy(days = listOf(original.days.first())),
                original.copy(
                    days =
                        listOf(
                            original.days.first().copy(workDate = "2026-02-30"),
                            original.days.last(),
                        )
                ),
                original.copy(
                    days =
                        listOf(
                            original.days.first(),
                            original.days.last().copy(portion = "SECOND_HALF"),
                        )
                ),
                original.copy(excludedDates = emptyList()),
                original.copy(excludedDates = listOf("2026-10-16", "2026-10-17")),
                original.copy(excludedDates = listOf("2026-10-17", "2026-10-17")),
                original.copy(excludedDates = listOf("2026-10-18")),
                original.copy(chargedDays = "1.25"),
                original.copy(chargedDays = "2"),
                original.copy(balances = emptyList()),
                original.copy(balances = listOf(balance, balance)),
                original.copy(balances = listOf(balance.copy(year = 2027))),
                original.copy(balances = listOf(balance.copy(requestedDays = "1"))),
                original.copy(balances = listOf(balance.copy(availableDays = "1"))),
                original.copy(balances = listOf(balance.copy(remainingDays = "9.5"))),
                original.copy(balances = listOf(balance.copy(consumedDays = "-1"))),
                original.copy(balances = listOf(balance.copy(version = -1))),
            )) {
            f.value = invalid
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.preview(f.base.access, f.selection))
                    .failure
                    .kind,
            )
        }
        f.value = original
        assertIs<AppResult.Success<LeaveRequestPreview>>(
            repository.preview(f.base.access, f.selection)
        )
    }

    @Test
    fun twoYearRequestsPreserveTheSeparateLedgerProjections() = runTest {
        val f = LeavePreparationDataFixture()
        val selection =
            f.selection.copy(
                days =
                    listOf(
                        LeaveRequestedDay(LocalDate(2026, 12, 31), LeavePortion.FULL),
                        LeaveRequestedDay(LocalDate(2027, 1, 1), LeavePortion.FIRST_HALF),
                    )
            )
        f.value =
            f.value.copy(
                days =
                    listOf(
                        f.value.days
                            .first()
                            .copy(
                                workDate = "2026-12-31",
                                startsAt = "2026-12-31T01:00:00Z",
                                endsAt = "2026-12-31T10:00:00Z",
                            ),
                        f.value.days
                            .last()
                            .copy(
                                workDate = "2027-01-01",
                                startsAt = "2027-01-01T01:00:00Z",
                                endsAt = "2027-01-01T10:00:00Z",
                            ),
                    ),
                excludedDates = emptyList(),
                balances =
                    listOf(
                        LeavePreviewBalanceDto(2026, "10.5", "1", "0.5", "1", "9.5", 3),
                        LeavePreviewBalanceDto(2027, "12", "0", "0", "0.5", "11.5", 0),
                    ),
            )
        val repository = RemoteLeavePreparationRepository(f)
        val preview =
            assertIs<AppResult.Success<LeaveRequestPreview>>(
                    repository.preview(f.base.access, selection)
                )
                .value
        assertEquals(listOf(2026, 2027), preview.balances.map { it.year })
        assertEquals(listOf(LeaveDays(19), LeaveDays(23)), preview.balances.map { it.remaining })
        f.value = f.value.copy(balances = f.value.balances.reversed())
        assertIs<AppResult.Failed>(repository.preview(f.base.access, selection))
    }

    @Test
    fun catalogPagesRejectInvalidOrderingOwnershipBoundsAndCursorProgress() {
        val f = LeavePreparationDataFixture()
        val selection = LeaveTypeSelection(LocalDate(2026, 10, 15))
        val types =
            (0..19).map { index ->
                f.type.copy(
                    id = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}",
                    code = "TYPE_${index.toString().padStart(2, '0')}",
                )
            }
        val page = LeavePageDto(types, "TYPE_19")
        assertEquals("TYPE_19", page.toTypes(selection).nextCursor)
        assertEquals(20, page.toTypes(selection).items.size)
        assertFalse(
            LeavePageDto(listOf(f.type.copy(active = false)))
                .toTypes(selection)
                .items
                .single()
                .active
        )
        for (invalid in
            listOf(
                page.copy(items = types.reversed()),
                page.copy(items = types + types.last()),
                page.copy(items = types.dropLast(1)),
                page.copy(nextCursor = "TYPE_18"),
                LeavePageDto(listOf(f.type, f.type)),
                LeavePageDto(listOf(f.type.copy(id = "../other"))),
                LeavePageDto(listOf(f.type.copy(code = "MixedCase"))),
                LeavePageDto(listOf(f.type.copy(name = ""))),
                LeavePageDto(listOf(f.type.copy(effectiveFrom = "2026-10-16"))),
                LeavePageDto(listOf(f.type.copy(appliedRevision = 1))),
                LeavePageDto(listOf(f.type.copy(version = 9_007_199_254_740_992L))),
            )) assertFailsWith<IllegalArgumentException> { invalid.toTypes(selection) }
        assertFailsWith<IllegalArgumentException> {
            page.toTypes(selection.copy(after = "TYPE_00"))
        }
    }

    @Test
    fun domainFailuresKeepTheirClassificationAndCancellationDoesNotBecomeAResult() = runTest {
        val f = LeavePreparationDataFixture()
        val repository = RemoteLeavePreparationRepository(f)
        val rejected =
            Failure(
                FailureKind.AccessDenied,
                "Access unavailable",
                code = "company_module_disabled",
            )
        f.failure = RequestFailureException(rejected)
        assertEquals(AppResult.Failed(rejected), repository.preview(f.base.access, f.selection))
        assertEquals(
            AppResult.Failed(rejected),
            repository.types(f.base.access, LeaveTypeSelection(LocalDate(2026, 10, 15))),
        )
        f.failure = CancellationException()
        assertFailsWith<CancellationException> { repository.preview(f.base.access, f.selection) }
        assertFailsWith<CancellationException> {
            repository.types(f.base.access, LeaveTypeSelection(LocalDate(2026, 10, 15)))
        }
        val calls = f.calls
        assertIs<AppResult.Failed>(
            repository.preview(f.base.access.copy(companyId = "../other"), f.selection)
        )
        assertEquals(calls, f.calls)
    }

    @Test
    fun boundedProblemResponsesPreserveServerValidationCodes() = runTest {
        val f = LeavePreparationDataFixture()
        var oversized = false
        val client =
            createHttpClient(
                MockEngine {
                    if (oversized)
                        respond(
                            " ".repeat(262_145),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    else
                        respond(
                            """{"type":"about:blank","title":"Cannot prepare request","status":409,"code":"insufficient_leave_balance"}""",
                            HttpStatusCode.Conflict,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository =
                RemoteLeavePreparationRepository(ApiLeavePreparationDataSource(LeaveApi(client)))
            assertEquals(
                "insufficient_leave_balance",
                assertIs<AppResult.Failed>(repository.preview(f.base.access, f.selection))
                    .failure
                    .code,
            )
            oversized = true
            assertIs<AppResult.Failed>(repository.preview(f.base.access, f.selection))
        } finally {
            client.close()
        }
    }
}
