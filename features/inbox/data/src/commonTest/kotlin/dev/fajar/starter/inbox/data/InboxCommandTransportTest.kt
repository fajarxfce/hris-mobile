package dev.fajar.starter.inbox.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.data.api.InboxApi
import dev.fajar.starter.inbox.data.datasources.*
import dev.fajar.starter.inbox.data.dto.*
import dev.fajar.starter.inbox.data.repositories.RemoteInboxRepository
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.network.createHttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class InboxCommandTransportTest {
    private val command =
        InboxCommand(
            "10000000-0000-4000-8000-000000000001",
            "20000000-0000-4000-8000-000000000001",
            "https://hris.example.test/api/v1/",
            "30000000-0000-4000-8000-000000000001",
            "40000000-0000-4000-8000-000000000001",
            7,
            InboxAction.ACKNOWLEDGE,
            Instant.parse("2026-10-11T00:00:00Z"),
        )

    @Test
    fun transmissionUsesTheImmutableOperationHeaderAndObservedVersion() = runTest {
        var calls = 0
        val client =
            createHttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(
                        "/api/v1/companies/${command.companyId}/inbox/${command.itemId}/acknowledge",
                        request.url.encodedPath,
                    )
                    assertEquals(command.id, request.headers["Idempotency-Key"])
                    assertEquals(
                        buildJsonObject { put("expectedVersion", 7) },
                        Json.parseToJsonElement((request.body as TextContent).text),
                    )
                    respond(
                        """{"id":"${command.itemId}","version":8}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                command.audience,
            )
        try {
            val repository = RemoteInboxRepository(ApiInboxRemoteDataSource(InboxApi(client)))
            repeat(2) {
                assertEquals(
                    AppResult.Success(InboxActionReceipt(command.itemId, 8)),
                    repository.act("session", command),
                )
            }
            assertEquals(2, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun receiptIdentityAndVersionAreValidatedInsideTheHttpBoundary() = runTest {
        var response = InboxActionReceiptDto(command.itemId, 8)
        var cancelled = false
        var calls = 0
        val source =
            object : InboxRemoteDataSource {
                override suspend fun unreadCount(
                    sessionId: String,
                    companyId: String,
                ): InboxUnreadCountDto = error("unused")

                override suspend fun item(
                    sessionId: String,
                    companyId: String,
                    id: String,
                ): InboxItemDto = error("unused")

                override suspend fun act(
                    sessionId: String,
                    companyId: String,
                    id: String,
                    operationId: String,
                    action: String,
                    request: InboxActionRequest,
                ): InboxActionReceiptDto {
                    calls++
                    if (cancelled) throw CancellationException()
                    return response
                }
            }
        val repository = RemoteInboxRepository(source)
        for (invalid in
            listOf(
                response.copy(id = command.id),
                response.copy(version = 6),
                response.copy(version = 9),
            )) {
            response = invalid
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.act("session", command)).failure.kind,
            )
        }
        response = InboxActionReceiptDto(command.itemId, command.expectedVersion)
        assertIs<AppResult.Success<InboxActionReceipt>>(repository.act("session", command))
        val previousCalls = calls
        assertIs<AppResult.Failed>(repository.act("session", command.copy(itemId = "../other")))
        assertEquals(previousCalls, calls)
        cancelled = true
        assertFailsWith<CancellationException> { repository.act("session", command) }
    }
}
