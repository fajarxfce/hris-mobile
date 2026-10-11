package dev.fajar.starter.leave.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.api.LeaveApi
import dev.fajar.starter.leave.data.datasources.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.repositories.RemoteLeaveRepository
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.network.createHttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LeaveCommandTransportTest {
    private val command =
        LeaveCommand(
            "10000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000001",
            "https://hris.example.test/api/v1/",
            "30000000-0000-4000-8000-000000000001",
            "40000000-0000-4000-8000-000000000001",
            7,
            LeaveRequestAction.WITHDRAW,
            "Plans changed",
            Instant.parse("2026-10-12T00:00:00Z"),
        )

    @Test
    fun everyActionUsesItsContractAndReusesTheExactOperationVersionAndReason() = runTest {
        for ((action, path) in
            listOf(
                LeaveRequestAction.WITHDRAW to "withdraw",
                LeaveRequestAction.WITHDRAW_CANCELLATION to "withdraw",
                LeaveRequestAction.REQUEST_CANCELLATION to "cancellation",
            )) {
            var calls = 0
            val client =
                createHttpClient(
                    MockEngine { request ->
                        calls++
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals(
                            "/api/v1/companies/${command.companyId}/leave/requests/${command.requestId}/$path",
                            request.url.encodedPath,
                        )
                        assertEquals(command.id, request.headers["Idempotency-Key"])
                        assertEquals(
                            buildJsonObject {
                                put("version", 7)
                                put("reason", "Plans changed")
                            },
                            Json.parseToJsonElement((request.body as TextContent).text),
                        )
                        respond(
                            """{"id":"${command.requestId}","version":8}""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                    command.audience,
                )
            try {
                val repository = RemoteLeaveRepository(ApiLeaveDataSource(LeaveApi(client)))
                repeat(2) {
                    assertEquals(
                        AppResult.Success(LeaveActionReceipt(command.requestId, 8)),
                        repository.act("session", command.copy(action = action)),
                    )
                }
                assertEquals(2, calls)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun malformedReceiptsAreFailuresAndCancellationIsNeverConvertedIntoAnError() = runTest {
        var response = LeaveActionReceiptDto(command.requestId, 8)
        var cancelled = false
        var calls = 0
        val source =
            object : LeaveRemoteDataSource by LeaveDataFixture() {
                override suspend fun act(
                    sessionId: String,
                    companyId: String,
                    requestId: String,
                    operationId: String,
                    action: String,
                    request: LeaveActionRequest,
                ): LeaveActionReceiptDto {
                    calls++
                    if (cancelled) throw CancellationException()
                    return response
                }
            }
        val repository = RemoteLeaveRepository(source)
        for (invalid in
            listOf(
                response.copy(id = command.id),
                response.copy(version = 7),
                response.copy(version = 9),
            )) {
            response = invalid
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.act("session", command)).failure.kind,
            )
        }
        val previousCalls = calls
        assertIs<AppResult.Failed>(repository.act("session", command.copy(requestId = "../other")))
        assertEquals(previousCalls, calls)
        cancelled = true
        assertFailsWith<CancellationException> { repository.act("session", command) }
    }
}
