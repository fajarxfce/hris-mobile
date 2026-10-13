package dev.fajar.starter.expenses.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.api.ExpenseApi
import dev.fajar.starter.expenses.data.datasources.ApiExpenseDataSource
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.repositories.RemoteExpenseSubmissionRepository
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expectedReceiptVersion
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class ExpenseCommandTransportTest {
    @Test
    fun everyRetryKeepsItsMethodBodyVersionAndOperationIncludingExactLineAmounts() = runTest {
        val f = ExpenseCommandDataFixture()
        val bodies = mutableMapOf<String, MutableList<String>>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    val command = f.commands.single { it.id == request.headers["Idempotency-Key"] }
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    val suffix =
                        when (val payload = command.payload) {
                            is ExpenseCommandPayload.SaveDraft -> "claims/${command.claimId}/draft"
                            is ExpenseCommandPayload.Submit -> "claims/${command.claimId}/submit"
                            is ExpenseCommandPayload.Cancel -> "claims/${command.claimId}/cancel"
                            is ExpenseCommandPayload.Withdraw ->
                                "submissions/${payload.submissionId}/withdraw"
                        }
                    assertEquals(
                        if (command.payload is ExpenseCommandPayload.SaveDraft) HttpMethod.Put
                        else HttpMethod.Post,
                        request.method,
                    )
                    assertEquals(
                        "/api/v1/companies/${command.companyId}/expenses/$suffix",
                        request.url.encodedPath,
                    )
                    val text = (request.body as TextContent).text
                    bodies.getOrPut(command.id) { mutableListOf() }.add(text)
                    val body = Json.parseToJsonElement(text).jsonObject
                    assertEquals(command.reason, body["reason"]?.jsonPrimitive?.content)
                    assertFalse(
                        "accountId" in body ||
                            "timezone" in body ||
                            "employeeNumber" in body ||
                            "currency" in body
                    )
                    when (val payload = command.payload) {
                        is ExpenseCommandPayload.SaveDraft -> {
                            assertEquals(
                                payload.expectedVersion,
                                body["expectedVersion"]?.jsonPrimitive?.longOrNull,
                            )
                            assertEquals(
                                command.employeeId,
                                body["employmentId"]?.jsonPrimitive?.content,
                            )
                            assertEquals(payload.title, body["title"]?.jsonPrimitive?.content)
                            val line = body["lines"]!!.jsonArray.single().jsonObject
                            assertEquals(f.f.line.id, line["id"]!!.jsonPrimitive.content)
                            assertTrue(line["amount"]!!.jsonPrimitive.isString)
                            assertEquals("120000.25", line["amount"]!!.jsonPrimitive.content)
                            assertEquals("2026-10-12", line["occurredOn"]!!.jsonPrimitive.content)
                        }
                        is ExpenseCommandPayload.Submit -> {
                            assertEquals(
                                payload.submissionId,
                                body["submissionId"]!!.jsonPrimitive.content,
                            )
                            assertEquals(
                                payload.expectedVersion,
                                body["expectedVersion"]!!.jsonPrimitive.long,
                            )
                        }
                        is ExpenseCommandPayload.Cancel ->
                            assertEquals(setOf("expectedVersion", "reason"), body.keys)
                        is ExpenseCommandPayload.Withdraw ->
                            assertEquals(setOf("expectedVersion", "reason"), body.keys)
                    }
                    respond(
                        Json.encodeToString(
                            ExpenseCommandReceiptDto(
                                command.claimId,
                                command.expectedReceiptVersion(),
                            )
                        ),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repo = RemoteExpenseSubmissionRepository(ApiExpenseDataSource(ExpenseApi(client)))
            repeat(2) {
                for (command in f.commands) assertEquals(
                    AppResult.Success(
                        ExpenseCommandReceipt(command.claimId, command.expectedReceiptVersion())
                    ),
                    repo.send(f.access.sessionId, command),
                )
            }
            assertEquals(5, bodies.size)
            assertTrue(bodies.values.all { it.size == 2 && it[0] == it[1] })
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidInputAndMismatchedReceiptsRemainInsideTheBoundaryWhileCancellationEscapes() =
        runTest {
            val f = ExpenseCommandDataFixture()
            val repo = RemoteExpenseSubmissionRepository(f.source)
            for (command in f.commands) for (receipt in
                listOf(
                    ExpenseCommandReceiptDto(command.id, command.expectedReceiptVersion()),
                    ExpenseCommandReceiptDto(command.claimId, -1),
                    ExpenseCommandReceiptDto(command.claimId, command.expectedReceiptVersion() + 1),
                )) {
                f.receipt = receipt
                assertEquals(
                    FailureKind.Unexpected,
                    assertIs<AppResult.Failed>(repo.send(f.access.sessionId, command)).failure.kind,
                )
            }
            val before = f.calls
            val payload = f.create.payload as ExpenseCommandPayload.SaveDraft
            for (command in
                listOf(
                    f.create.copy(companyId = "../other"),
                    f.create.copy(reason = " "),
                    f.create.copy(
                        payload = payload.copy(lines = List(21) { payload.lines.single() })
                    ),
                    f.cancel.copy(payload = ExpenseCommandPayload.Cancel(-1)),
                )) assertIs<AppResult.Failed>(repo.send(f.access.sessionId, command))
            assertEquals(before, f.calls)
            f.failure = CancellationException("Cancelled")
            for (command in f.commands) assertFailsWith<CancellationException> {
                repo.send(f.access.sessionId, command)
            }
        }

    @Test
    fun structuredFailuresAreLocalizableAndOversizedReceiptsNeverConfirmDelivery() = runTest {
        val f = ExpenseCommandDataFixture()
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
                            """{"status":409,"code":"stale_version","detail":"private detail"}""",
                            HttpStatusCode.Conflict,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repo = RemoteExpenseSubmissionRepository(ApiExpenseDataSource(ExpenseApi(client)))
            val failure = assertIs<AppResult.Failed>(repo.send(f.access.sessionId, f.edit)).failure
            assertEquals(FailureKind.Conflict, failure.kind)
            assertEquals("stale_version", failure.code)
            assertFalse(failure.message.contains("private"))
            oversized = true
            assertIs<AppResult.Failed>(repo.send(f.access.sessionId, f.edit))
        } finally {
            client.close()
        }
    }
}
