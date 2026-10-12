package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.data.overtime.api.OvertimeApi
import dev.fajar.starter.workforce.data.overtime.datasources.ApiOvertimeDataSource
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.repositories.RemoteOvertimeRepository
import dev.fajar.starter.workforce.domain.overtime.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class OvertimeRepositoryTest {
    @Test
    fun transportPinsCompanyEmployeeDatesAndBoundedHistory() = runTest {
        val f = OvertimeDataFixture()
        val client =
            HttpClient(
                MockEngine { request ->
                    val path = "/api/v1/companies/${f.access.companyId}/workforce/overtime"
                    val body =
                        if (request.url.encodedPath == path) {
                            assertEquals(f.base.employee.id, request.url.parameters["employeeId"])
                            assertEquals("2026-10-12", request.url.parameters["from"])
                            assertEquals("2026-10-18", request.url.parameters["until"])
                            assertEquals("20", request.url.parameters["limit"])
                            assertEquals("PLANNED", request.url.parameters["status"])
                            Json.encodeToString(f.page)
                        } else {
                            assertEquals("$path/${f.request.id}", request.url.encodedPath)
                            assertEquals("10", request.url.parameters["historyLimit"])
                            assertNull(request.url.parameters["historyAfter"])
                            Json.encodeToString(f.detail)
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
            val repo = RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
            val page =
                assertIs<AppResult.Success<OvertimePage<OvertimeSummary>>>(
                        repo.requests(
                            f.access,
                            f.base.employee.id,
                            f.base.period,
                            OvertimeStatus.PLANNED,
                            null,
                        )
                    )
                    .value
            assertEquals(120, page.items.single().requested.workedMinutes)
            val details =
                assertIs<AppResult.Success<OvertimeDetails>>(repo.details(f.access, f.selection))
                    .value
            assertEquals(
                setOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW),
                details.actions,
            )
            assertEquals(f.request.reason, details.request.reason)
        } finally {
            client.close()
        }
    }

    @Test
    fun inconsistentDurationsSubmissionStateAndFrozenSchedulesAreRejected() = runTest {
        val f = OvertimeDataFixture()
        val repo = RemoteOvertimeRepository(f)
        for (input in
            listOf(
                f.request.copy(requested = f.requested.copy(workedMinutes = 119)),
                f.request.copy(timezone = "Unknown/Zone"),
                f.request.copy(schedule = f.base.workDay.copy(originVersion = null)),
                f.request.copy(version = 1),
                f.request.copy(status = "PENDING"),
                f.request.copy(approvedMinutes = 120),
                f.request.copy(decidedAt = f.base.now.toString()),
                f.request.copy(employeeId = "invalid"),
            )) {
            f.page = OvertimePageDto(listOf(input))
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        repo.requests(f.access, f.base.employee.id, f.base.period, null, null)
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun pagesRejectForeignEmployeesDuplicateIdsInvalidDatesAndNonProgress() = runTest {
        val f = OvertimeDataFixture()
        val repo = RemoteOvertimeRepository(f)
        for (input in
            listOf(
                OvertimePageDto(
                    listOf(f.request.copy(employeeId = "99999999-9999-9999-9999-999999999999"))
                ),
                OvertimePageDto(listOf(f.request, f.request)),
                OvertimePageDto(List(21) { f.request }),
                OvertimePageDto(listOf(f.request), f.request.id),
            )) {
            f.page = input
            assertIs<AppResult.Failed>(
                repo.requests(f.access, f.base.employee.id, f.base.period, null, null)
            )
        }
        f.page = OvertimePageDto(listOf(f.request))
        assertIs<AppResult.Failed>(
            repo.requests(f.access, f.base.employee.id, f.base.period, null, f.request.id)
        )
        assertIs<AppResult.Failed>(
            repo.requests(
                f.access,
                f.base.employee.id,
                f.base.period,
                OvertimeStatus.APPROVED,
                null,
            )
        )
    }

    @Test
    fun detailIdentityLatestHistoryAndAvailableActionsMustAgree() = runTest {
        val f = OvertimeDataFixture()
        val original = f.detail
        val repo = RemoteOvertimeRepository(f)
        for (input in
            listOf(
                original.copy(
                    request = f.request.copy(id = "99999999-9999-9999-9999-999999999999")
                ),
                original.copy(history = OvertimePageDto(emptyList())),
                original.copy(history = OvertimePageDto(listOf(f.planned, f.planned))),
                original.copy(actions = listOf("APPROVE")),
                original.copy(actions = listOf("WITHDRAW", "WITHDRAW")),
                original.copy(
                    approval =
                        OvertimeWorkflowDto(
                            "77777777-7777-7777-7777-777777777777",
                            "PENDING",
                            0,
                            listOf(emptyList()),
                            0,
                        )
                ),
            )) {
            f.detail = input
            assertIs<AppResult.Failed>(repo.details(f.access, f.selection))
        }
        f.detail = original
        assertIs<AppResult.Failed>(
            repo.details(
                f.access,
                f.selection.copy(employeeId = "99999999-9999-9999-9999-999999999999"),
            )
        )
    }

    @Test
    fun pendingAndApprovedFactsRetainActualTimeAndCurrentApprovalProgress() = runTest {
        val f = OvertimeDataFixture()
        val repo = RemoteOvertimeRepository(f)
        val actual = f.requested.copy(endsAt = "2026-10-12T13:00:00Z", workedMinutes = 60)
        val approvalId = "77777777-7777-7777-7777-777777777777"
        val submitted =
            f.request.copy(
                status = "PENDING",
                actual = actual,
                submittedBy = f.access.accountId,
                submittedAt = "2026-10-12T14:00:00Z",
                approvalId = approvalId,
                version = 1,
            )
        val history =
            OvertimeChangeDto(
                1,
                "ACTUAL_SUBMITTED",
                "PENDING",
                actual,
                0,
                approvalId,
                f.access.accountId,
                "2026-10-12T14:00:00Z",
                "Work completed",
            )
        f.detail =
            OvertimeDetailsDto(
                submitted,
                OvertimeWorkflowDto(approvalId, "BLOCKED", 0, listOf(emptyList()), 0),
                OvertimePageDto(listOf(history, f.planned)),
                listOf("WITHDRAW"),
            )
        assertEquals(
            OvertimeApprovalStatus.BLOCKED,
            assertIs<AppResult.Success<OvertimeDetails>>(repo.details(f.access, f.selection))
                .value
                .approval!!
                .status,
        )
        val approved =
            submitted.copy(
                status = "APPROVED",
                approvedMinutes = 60,
                decidedAt = "2026-10-12T15:00:00Z",
                version = 2,
            )
        f.detail =
            f.detail.copy(
                request = approved,
                approval = f.detail.approval!!.copy(status = "APPROVED", version = 1),
                history =
                    OvertimePageDto(
                        listOf(
                            history.copy(
                                revision = 2,
                                kind = "DECIDED",
                                status = "APPROVED",
                                approvedMinutes = 60,
                                reason = "",
                            ),
                            history,
                            f.planned,
                        )
                    ),
                actions = emptyList(),
            )
        assertEquals(
            60,
            assertIs<AppResult.Success<OvertimeDetails>>(repo.details(f.access, f.selection))
                .value
                .request
                .approvedMinutes,
        )
    }

    @Test
    fun cancellationPropagatesAndDtoListsCannotMutateMappedFacts() = runTest {
        val f = OvertimeDataFixture()
        val repo = RemoteOvertimeRepository(f)
        val history = f.detail.history.items.toMutableList()
        val actions = f.detail.actions.toMutableList()
        f.detail = f.detail.copy(history = OvertimePageDto(history), actions = actions)
        val result =
            assertIs<AppResult.Success<OvertimeDetails>>(repo.details(f.access, f.selection)).value
        history.clear()
        actions.clear()
        assertEquals(1, result.history.items.size)
        assertEquals(2, result.actions.size)
        f.failure = CancellationException("fixture")
        assertFailsWith<CancellationException> { repo.details(f.access, f.selection) }
    }

    @Test
    fun oversizedWireJsonFailsBeforeMappingAndDoesNotExposeRawMessages() = runTest {
        val f = OvertimeDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        "{\"padding\":\"" + "x".repeat(270_000) + "\"}",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val result =
                RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
                    .details(f.access, f.selection)
            assertIs<AppResult.Failed>(result)
            assertFalse(result.failure.message.contains("padding"))
        } finally {
            client.close()
        }
    }
}
