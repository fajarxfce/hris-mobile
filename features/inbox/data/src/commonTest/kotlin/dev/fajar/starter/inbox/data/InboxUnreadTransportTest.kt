package dev.fajar.starter.inbox.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.data.api.InboxApi
import dev.fajar.starter.inbox.data.datasources.ApiInboxRemoteDataSource
import dev.fajar.starter.inbox.data.repositories.RemoteInboxRepository
import dev.fajar.starter.network.createHttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class InboxUnreadTransportTest {
    @Test
    fun countHasItsOwnBoundedGetContractAndPreservesFailuresAndCancellation() = runTest {
        val company = "30000000-0000-4000-8000-000000000001"
        var payload = """{"unreadCount":2147483648}"""
        var status = HttpStatusCode.OK
        var cancelled = false
        var calls = 0
        val client =
            createHttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals(
                        "/api/v1/companies/$company/inbox/unread-count",
                        request.url.encodedPath,
                    )
                    assertTrue(request.url.parameters.isEmpty())
                    if (cancelled) throw CancellationException()
                    respond(payload, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteInboxRepository(ApiInboxRemoteDataSource(InboxApi(client)))
            assertEquals(
                AppResult.Success(2_147_483_648L),
                repository.unreadCount("session", company),
            )
            for (invalid in listOf("""{"unreadCount":-1}""", "{}", " ".repeat(4097))) {
                payload = invalid
                assertIs<AppResult.Failed>(repository.unreadCount("session", company))
            }
            payload = """{"code":"company_access_denied"}"""
            status = HttpStatusCode.Forbidden
            assertEquals(
                "company_access_denied",
                assertIs<AppResult.Failed>(repository.unreadCount("session", company)).failure.code,
            )
            val before = calls
            assertIs<AppResult.Failed>(repository.unreadCount("session", "../other"))
            assertEquals(before, calls)
            cancelled = true
            assertFailsWith<CancellationException> { repository.unreadCount("session", company) }
        } finally {
            client.close()
        }
    }
}
