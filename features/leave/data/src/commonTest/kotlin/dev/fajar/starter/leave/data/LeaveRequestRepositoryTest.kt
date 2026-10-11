package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.datasources.ApiLeaveDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.RemoteLeaveRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailureInvalidatesCache
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

class LeaveRequestRepositoryTest {
    @Test
    fun selfServiceTransportRetainsCurrentMetadataWhilePagingEarlierHistory() = runTest {
        val f = LeaveDataFixture()
        f.detail = f.detail.copy(status = "CANCELLED", version = 1, availableActions = emptySet())
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/leave/me/requests/${f.request.id}",
                        request.url.encodedPath,
                    )
                    assertEquals("10", request.url.parameters["historyLimit"])
                    assertEquals("1", request.url.parameters["historyAfter"])
                    assertNull(request.url.parameters["employeeId"])
                    respond(
                        Json.encodeToString(f.detail),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
            val details =
                assertIs<AppResult.Success<LeaveRequestDetails>>(
                        repository.details(f.access, LeaveRequestSelection(f.request.id, 1))
                    )
                    .value
            assertEquals(LeaveStatus.CANCELLED, details.status)
            assertEquals(1L, details.version)
            assertEquals(LeaveStatus.PENDING, details.history.items.single().status)
            assertEquals(LeaveDays(3), details.charged)
            assertEquals(LeavePortion.FIRST_HALF, details.days.last().portion)
            assertEquals("note.pdf", details.attachments.single().fileName)
            assertEquals(details, details.toDto().toDetails(LeaveRequestSelection(f.request.id, 1)))
        } finally {
            client.close()
        }
    }

    @Test
    fun mappingRejectsWrongRequestMalformedDatesDuplicateHistoryAndNonProgressingCursors() =
        runTest {
            val f = LeaveDataFixture()
            val original = f.detail
            val repository = RemoteLeaveRepository(f)
            val change = original.history.items.single()
            for (malformed in
                listOf(
                    original.copy(id = f.employee.id),
                    original.copy(chargedDays = "1.25"),
                    original.copy(chargedDays = "2"),
                    original.copy(days = original.days.reversed()),
                    original.copy(days = listOf(original.days.first(), original.days.first())),
                    original.copy(
                        days = listOf(original.days.first().copy(workDate = "2026-02-30"))
                    ),
                    original.copy(days = listOf(original.days.first().copy(chargedMinutes = 1000))),
                    original.copy(history = LeavePageDto(listOf(change, change))),
                    original.copy(history = LeavePageDto(listOf(change), "0")),
                    original.copy(version = 1),
                    original.copy(approval = original.approval.copy(currentStep = -1)),
                    original.copy(availableActions = setOf("UNKNOWN")),
                    original.copy(attachments = original.attachments + original.attachments),
                    original.copy(
                        attachments = listOf(original.attachments.single().copy(size = 0))
                    ),
                )) {
                f.detail = malformed
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(
                            repository.details(f.access, LeaveRequestSelection(f.request.id))
                        )
                        .failure
                        .kind,
                )
            }
            f.detail = original
            assertIs<AppResult.Failed>(
                repository.details(f.access, LeaveRequestSelection(f.request.id, 0))
            )
        }

    @Test
    fun historyPaginationMustUseTheLastReturnedRevisionAndMakeProgress() {
        val f = LeaveDataFixture()
        val history =
            (15L downTo 6).map {
                LeaveChangeDto(it, "DECIDED", "PENDING", f.now.toString(), "Review")
            }
        val dto = f.detail.copy(version = 15, history = LeavePageDto(history, "6"))
        val selection = LeaveRequestSelection(f.request.id)
        assertEquals("6", dto.toDetails(selection).history.nextCursor)
        for (cursor in listOf("15", "06", "5", "-1")) assertFailsWith<IllegalArgumentException> {
            dto.copy(history = dto.history.copy(nextCursor = cursor)).toDetails(selection)
        }
        assertFailsWith<IllegalArgumentException> {
            dto.toDetails(selection.copy(historyAfter = 15))
        }
    }

    @Test
    fun aScopedNotFoundProblemRemainsAnAuthoritativeFailure() = runTest {
        val f = LeaveDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"type":"about:blank","title":"Unavailable","status":404,"code":"leave_request_not_found"}""",
                        HttpStatusCode.NotFound,
                        headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val failure =
                assertIs<AppResult.Failed>(
                        RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
                            .details(f.access, LeaveRequestSelection(f.request.id))
                    )
                    .failure
            assertEquals("leave_request_not_found", failure.code)
            assertTrue(leaveFailureInvalidatesCache(failure))
        } finally {
            client.close()
        }
    }

    @Test
    fun responseBoundsAndCancellationCannotBecomeSuccessfulEmptyDetails() = runTest {
        val f = LeaveDataFixture()
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        " ".repeat(1_048_577),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            assertIs<AppResult.Failed>(
                RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
                    .details(f.access, LeaveRequestSelection(f.request.id))
            )
        } finally {
            client.close()
        }
        f.failure = CancellationException("Cancelled read")
        assertFailsWith<CancellationException> {
            RemoteLeaveRepository(f).details(f.access, LeaveRequestSelection(f.request.id))
        }
    }
}
