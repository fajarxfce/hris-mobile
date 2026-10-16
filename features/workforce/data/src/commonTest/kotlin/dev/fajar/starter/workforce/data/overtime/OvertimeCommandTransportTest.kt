package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.*
import dev.fajar.starter.workforce.data.overtime.api.OvertimeApi
import dev.fajar.starter.workforce.data.overtime.datasources.ApiOvertimeDataSource
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.data.overtime.repositories.RemoteOvertimeRepository
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.expectedReceiptVersion
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class OvertimeCommandTransportTest {
    @Test
    fun planActualAndWithdrawalKeepTheirOriginalBodiesVersionsAndOperationKeys() = runTest {
        val f = OvertimeCommandDataFixture()
        val bodies = mutableMapOf<String, MutableList<String>>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    val command = f.commands.single { it.id == request.headers["Idempotency-Key"] }
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    val suffix =
                        when (command.payload) {
                            is OvertimeCommandPayload.Plan -> ""
                            is OvertimeCommandPayload.Actual -> "/${command.requestId}/actual"
                            is OvertimeCommandPayload.Withdrawal -> "/${command.requestId}/withdraw"
                        }
                    assertEquals(
                        "/api/v1/companies/${command.companyId}/workforce/overtime$suffix",
                        request.url.encodedPath,
                    )
                    val text = (request.body as TextContent).text
                    bodies.getOrPut(command.id) { mutableListOf() } += text
                    val body = Json.parseToJsonElement(text).jsonObject
                    assertEquals(command.reason, body["reason"]?.jsonPrimitive?.content)
                    assertFalse(
                        "workedMinutes" in text ||
                            "employeeNumber" in body ||
                            "accountId" in body ||
                            "timezone" in body ||
                            "workingRevision" in body
                    )
                    when (val payload = command.payload) {
                        is OvertimeCommandPayload.Plan -> {
                            assertEquals(command.requestId, body["id"]?.jsonPrimitive?.content)
                            assertEquals(
                                command.employeeId,
                                body["employeeId"]?.jsonPrimitive?.content,
                            )
                            assertEquals(7, body["expectedEmploymentVersion"]?.jsonPrimitive?.long)
                            assertEquals(
                                command.workDate.toString(),
                                body["workDate"]?.jsonPrimitive?.content,
                            )
                            assertEquals(
                                payload.planningRevision,
                                body["planningRevision"]?.jsonPrimitive?.content,
                            )
                            assertEquals(
                                payload.requested.startsAt.toString(),
                                body["requested"]
                                    ?.jsonObject
                                    ?.get("startsAt")
                                    ?.jsonPrimitive
                                    ?.content,
                            )
                        }
                        is OvertimeCommandPayload.Actual -> {
                            assertEquals(4, body["expectedVersion"]?.jsonPrimitive?.long)
                            assertEquals(
                                payload.actual.endsAt.toString(),
                                body["actual"]?.jsonObject?.get("endsAt")?.jsonPrimitive?.content,
                            )
                            assertEquals(
                                payload.actual.breakMinutes,
                                body["actual"]?.jsonObject?.get("breakMinutes")?.jsonPrimitive?.int,
                            )
                        }
                        is OvertimeCommandPayload.Withdrawal -> {
                            assertEquals(5, body["expectedVersion"]?.jsonPrimitive?.long)
                            assertEquals(setOf("expectedVersion", "reason"), body.keys)
                        }
                    }
                    respond(
                        """{"id":"${command.requestId}","version":${command.expectedReceiptVersion()}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
            repeat(2) {
                f.commands.forEach { command ->
                    assertEquals(
                        AppResult.Success(
                            OvertimeCommandReceipt(
                                command.requestId,
                                command.expectedReceiptVersion(),
                            )
                        ),
                        repository.send(f.access.sessionId, command),
                    )
                }
            }
            assertEquals(3, bodies.size)
            assertTrue(bodies.values.all { it.size == 2 && it[0] == it[1] })
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidReceiptsAndInputFailInsideTheBoundaryAndCancellationPropagates() = runTest {
        val f = OvertimeCommandDataFixture()
        val repository = RemoteOvertimeRepository(f.source)
        for (command in f.commands) {
            for (receipt in
                listOf(
                    OvertimeCommandReceiptDto(command.id, command.expectedReceiptVersion()),
                    OvertimeCommandReceiptDto(command.requestId, -1),
                    OvertimeCommandReceiptDto(
                        command.requestId,
                        command.expectedReceiptVersion() + 1,
                    ),
                )) {
                f.source.receipt = receipt
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(repository.send(f.access.sessionId, command))
                        .failure
                        .kind,
                )
            }
        }
        for (invalid in
            listOf(
                f.plan.copy(companyId = "../other"),
                f.plan.copy(reason = " "),
                f.actual.copy(payload = OvertimeCommandPayload.Withdrawal(999)),
                f.plan.copy(
                    payload =
                        (f.plan.payload as OvertimeCommandPayload.Plan).copy(planningRevision = "")
                ),
            )) {
            assertIs<AppResult.Failed>(repository.send(f.access.sessionId, invalid))
        }
        f.source.failure = CancellationException()
        f.commands.forEach { command ->
            assertFailsWith<CancellationException> { repository.send(f.access.sessionId, command) }
        }
    }

    @Test
    fun structuredRejectionsStayLocalizableAndOversizedReceiptsCannotConfirmDelivery() = runTest {
        val f = OvertimeCommandDataFixture()
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
                            """{"type":"about:blank","title":"Internal detail","status":409,"code":"overtime_planning_changed"}""",
                            HttpStatusCode.Conflict,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteOvertimeRepository(ApiOvertimeDataSource(OvertimeApi(client)))
            val failure =
                assertIs<AppResult.Failed>(repository.send(f.access.sessionId, f.plan)).failure
            assertEquals("overtime_planning_changed", failure.code)
            assertEquals(FailureKind.Conflict, failure.kind)
            assertFalse(failure.message.contains("Internal detail"))
            oversized = true
            f.commands.forEach {
                assertIs<AppResult.Failed>(repository.send(f.access.sessionId, it))
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun storedPayloadsValidateProtocolFactsAndCalculatedDurations() {
        val f = OvertimeCommandDataFixture()
        f.commands.forEach {
            assertEquals(
                it,
                Json.decodeFromString<OvertimeCommandDto>(Json.encodeToString(it.toDto()))
                    .toCommand(),
            )
        }
        val dto = f.plan.toDto()
        val plan = dto.payload as OvertimeCommandPayloadDto.Plan
        for (invalid in
            listOf(
                dto.copy(id = "../other"),
                dto.copy(employeeId = "INVALID"),
                dto.copy(timezone = "not/a/zone"),
                dto.copy(workDate = "2026-02-30"),
                dto.copy(reason = " leading space"),
                dto.copy(createdAt = "invalid"),
                dto.copy(payload = plan.copy(planningRevision = "a".repeat(65))),
                dto.copy(payload = plan.copy(requested = plan.requested.copy(workedMinutes = 999))),
                dto.copy(payload = OvertimeCommandPayloadDto.Withdrawal(999)),
            )) assertFails { invalid.toCommand() }
    }

    @Test
    fun outcomeEvidenceExcludesRawMessagesAndRejectsImpossiblePhaseOrReceiptCombinations() {
        val outcome =
            OvertimeCommandOutcome.Unconfirmed(
                Failure(
                    FailureKind.Timeout,
                    "Sensitive internal detail",
                    code = "client.overtime_timeout",
                    correlationId = "trace-1",
                )
            )
        val dto = outcome.toDto(2)
        assertFalse(Json.encodeToString(dto).contains("Sensitive internal detail"))
        assertEquals(
            "trace-1",
            assertIs<OvertimeCommandOutcome.Unconfirmed>(dto.toOutcome(0)).failure?.correlationId,
        )
        for (version in listOf(0L, 8L)) assertEquals(
            OvertimeCommandOutcome.Confirmed(version),
            OvertimeCommandOutcome.Confirmed(version).toDto(1).toOutcome(version),
        )
        for (invalid in
            listOf(
                dto.copy(attempts = 0),
                dto.copy(attempts = 1_001),
                dto.copy(phase = "PENDING"),
                dto.copy(receiptVersion = 1),
                dto.copy(failure = dto.failure!!.copy(code = "unsafe\ncode")),
            )) assertFails { invalid.toOutcome(0) }
        assertFails { OvertimeCommandOutcome.Confirmed(1).toDto(1).toOutcome(0) }
    }
}
