package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.datasources.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.data.repositories.RemoteLeaveRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LeaveSubmissionTransportTest {
    @Test
    fun retriesKeepTheExactSubmittedDatesReasonEvidencePreviewAndOperation() = runTest {
        val f = LeaveSubmissionDataFixture()
        val command =
            f.command.copy(attachmentRevisionIds = listOf("20000000-0000-4000-8000-000000000001"))
        var calls = 0
        val bodies = mutableListOf<String>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(
                        "/api/v1/companies/${command.companyId}/leave/requests",
                        request.url.encodedPath,
                    )
                    assertEquals(command.id, request.headers["Idempotency-Key"])
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    bodies += (request.body as TextContent).text
                    val body = Json.parseToJsonElement(bodies.last()).jsonObject
                    assertEquals(command.requestId, body["id"]?.jsonPrimitive?.content)
                    assertEquals(
                        command.selection.employeeId,
                        body["employeeId"]?.jsonPrimitive?.content,
                    )
                    assertEquals("a".repeat(64), body["previewRevision"]?.jsonPrimitive?.content)
                    assertEquals(
                        "Family appointment\nPersonal leave",
                        body["reason"]?.jsonPrimitive?.content,
                    )
                    assertEquals(
                        listOf("2026-10-15", "2026-10-16", "2026-10-17"),
                        body["days"]?.jsonArray?.map {
                            it.jsonObject["workDate"]!!.jsonPrimitive.content
                        },
                    )
                    assertEquals(
                        listOf("FULL", "FIRST_HALF", "FULL"),
                        body["days"]?.jsonArray?.map {
                            it.jsonObject["portion"]!!.jsonPrimitive.content
                        },
                    )
                    assertEquals(
                        command.attachmentRevisionIds,
                        body["attachmentRevisionIds"]?.jsonArray?.map { it.jsonPrimitive.content },
                    )
                    assertFalse("typeName" in body || "chargedHalfDays" in body)
                    respond(
                        """{"id":"${command.requestId}","version":0}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
            repeat(2) {
                assertEquals(
                    AppResult.Success(LeaveSubmissionReceipt(command.requestId, 0)),
                    repository.submit(f.access.sessionId, command),
                )
            }
            assertEquals(2, calls)
            assertEquals(bodies.first(), bodies.last())
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidReceiptsAndInputFailWithinTheBoundaryWhileCancellationPropagates() = runTest {
        val f = LeaveSubmissionDataFixture()
        var receipt = LeaveActionReceiptDto(f.command.requestId, 0)
        var cancelled = false
        var calls = 0
        val source =
            object : LeaveRemoteDataSource by f.preparation.base {
                override suspend fun submit(
                    sessionId: String,
                    companyId: String,
                    operationId: String,
                    request: LeaveSubmissionRequest,
                ): LeaveActionReceiptDto {
                    calls++
                    if (cancelled) throw CancellationException()
                    return receipt
                }
            }
        val repository = RemoteLeaveRepository(source)
        for (invalid in
            listOf(
                receipt.copy(id = f.command.id),
                receipt.copy(version = 1),
                receipt.copy(version = -1),
            )) {
            receipt = invalid
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.submit(f.access.sessionId, f.command))
                    .failure
                    .kind,
            )
        }
        val previous = calls
        for (invalid in
            listOf(
                f.command.copy(companyId = "../other"),
                f.command.copy(previewRevision = ""),
                f.command.copy(selection = f.command.selection.copy(days = emptyList())),
                f.command.copy(reason = " "),
                f.command.copy(attachmentRevisionIds = listOf("../other")),
            )) {
            assertIs<AppResult.Failed>(repository.submit(f.access.sessionId, invalid))
        }
        assertEquals(previous, calls)
        cancelled = true
        assertFailsWith<CancellationException> { repository.submit(f.access.sessionId, f.command) }
    }

    @Test
    fun persistedSubmissionRejectsCorruptDatesAndKeepsOutcomeEvidenceCompatibleWithActions() {
        val f = LeaveSubmissionDataFixture()
        val dto = f.command.toDto()
        assertEquals(
            f.command,
            Json.decodeFromString<LeaveSubmissionCommandDto>(Json.encodeToString(dto))
                .toSubmissionCommand(),
        )
        for (invalid in
            listOf(
                dto.copy(days = dto.days.reversed()),
                dto.copy(days = dto.days + dto.days.first()),
                dto.copy(chargedHalfDays = 0),
                dto.copy(chargedHalfDays = 99),
                dto.copy(previewRevision = "a".repeat(65)),
                dto.copy(typeName = ""),
                dto.copy(createdAt = "invalid"),
            )) {
            assertFails { invalid.toSubmissionCommand() }
        }
        val sent =
            LeaveCommandOutcome.Unconfirmed(
                Failure(
                    FailureKind.Timeout,
                    "Sensitive internal detail",
                    code = "client.leave_timeout",
                )
            )
        val outcome = sent.toDto(2)
        assertFalse(Json.encodeToString(outcome).contains("Sensitive internal detail"))
        assertIs<LeaveCommandOutcome.Unconfirmed>(outcome.toOutcome(0))
        assertEquals(
            LeaveCommandOutcome.Confirmed(0),
            LeaveCommandOutcome.Confirmed(0).toDto(1).toOutcome(0),
        )
        assertEquals(
            LeaveCommandOutcome.Confirmed(4),
            LeaveCommandOutcome.Confirmed(4).toDto(1).toOutcome(4),
        )
        assertFailsWith<IllegalArgumentException> {
            LeaveCommandOutcome.Confirmed(1).toDto(1).toOutcome(0)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaveCommandOutcome.Unconfirmed().toDto(0).toOutcome(0)
        }
    }

    @Test
    fun aChangedPreviewRemainsAStableConflictAndOversizedReceiptsAreNotConfirmed() = runTest {
        val f = LeaveSubmissionDataFixture()
        var oversized = false
        val client =
            createHttpClient(
                MockEngine {
                    if (oversized)
                        respond(
                            " ".repeat(4_097),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    else
                        respond(
                            """{"type":"about:blank","title":"Preview changed","status":409,"code":"leave_preview_changed"}""",
                            HttpStatusCode.Conflict,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
            val failure =
                assertIs<AppResult.Failed>(repository.submit(f.access.sessionId, f.command)).failure
            assertEquals("leave_preview_changed", failure.code)
            assertEquals(FailureKind.Conflict, failure.kind)
            oversized = true
            assertIs<AppResult.Failed>(repository.submit(f.access.sessionId, f.command))
        } finally {
            client.close()
        }
    }
}
