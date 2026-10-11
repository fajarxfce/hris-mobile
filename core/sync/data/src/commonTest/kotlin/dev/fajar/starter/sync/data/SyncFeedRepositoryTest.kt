package dev.fajar.starter.sync.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.data.api.SyncFeedApi
import dev.fajar.starter.sync.data.datasources.*
import dev.fajar.starter.sync.data.dto.*
import dev.fajar.starter.sync.data.repositories.RemoteSyncFeedRepository
import dev.fajar.starter.sync.domain.entities.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.url
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class SyncFeedRepositoryTest {
    private val company = "11111111-1111-1111-1111-111111111111"
    private val id = "22222222-2222-2222-2222-222222222222"
    private val selection = setOf(SyncCollection.INBOX)
    private val resource = SyncResourceDto("INBOX", id, 1)
    private val page =
        SyncBootstrapDto(
            listOf("INBOX"),
            listOf(resource),
            changesCursor = "changes",
            serverTime = "2026-10-11T00:00:00Z",
        )

    @Test
    fun transportKeepsExplicitSelectionAndOpaqueCursorsAsBoundedQueryParameters() = runTest {
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals(
                        "/api/v1/companies/$company/sync/bootstrap",
                        request.url.encodedPath,
                    )
                    assertEquals("INBOX", request.url.parameters["collections"])
                    assertEquals("50", request.url.parameters["limit"])
                    assertEquals("opaque&a=b", request.url.parameters["cursor"])
                    respond(
                        Json.encodeToString(page),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                defaultRequest { url("https://hris.example.test/api/v1/") }
            }
        try {
            val repository =
                RemoteSyncFeedRepository(ApiSyncFeedRemoteDataSource(SyncFeedApi(client)))
            assertIs<AppResult.Success<SyncBootstrapPage>>(
                repository.bootstrap("session", company, selection, "opaque&a=b")
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedAndNonProgressingBootstrapPagesCannotEscapeAsDomainValues() = runTest {
        val source = FeedSource(page)
        val repository = RemoteSyncFeedRepository(source)
        for (invalid in
            listOf(
                page.copy(items = listOf(resource, resource)),
                page.copy(items = List(51) { resource }),
                page.copy(items = listOf(resource.copy(collection = "PAYSLIPS"))),
                page.copy(items = listOf(resource.copy(version = -1))),
                page.copy(items = listOf(resource.copy(id = "../unexpected"))),
                page.copy(nextCursor = "previous", changesCursor = null),
                page.copy(nextCursor = "next"),
                page.copy(changesCursor = null),
                page.copy(serverTime = "not-an-instant"),
            )) {
            source.bootstrap = invalid
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(
                        repository.bootstrap("session", company, selection, "previous")
                    )
                    .failure
                    .kind,
            )
        }
    }

    @Test
    fun changesKeepOrderedTombstonesAndRejectUnknownOperationsAndEndlessPages() = runTest {
        val source = FeedSource(page)
        source.delta =
            source.delta.copy(
                items =
                    listOf(
                        SyncChangeDto("INBOX", id, 2, "UPSERT"),
                        SyncChangeDto("INBOX", id, 2, "DELETE"),
                    )
            )
        val repository = RemoteSyncFeedRepository(source)
        val result =
            assertIs<AppResult.Success<SyncChangePage>>(
                    repository.changes("session", company, selection, "previous")
                )
                .value
        assertEquals(
            listOf(SyncOperation.UPSERT, SyncOperation.DELETE),
            result.items.map { it.operation },
        )
        source.delta = source.delta.copy(cursor = "previous", hasMore = true)
        assertIs<AppResult.Failed>(repository.changes("session", company, selection, "previous"))
        source.delta =
            source.delta.copy(
                cursor = "next",
                items = listOf(SyncChangeDto("INBOX", id, 2, "UNKNOWN")),
            )
        assertIs<AppResult.Failed>(repository.changes("session", company, selection, "previous"))
    }

    @Test
    fun cancellationPropagatesAndInvalidRequestsNeverReachTheSource() = runTest {
        val source = FeedSource(page)
        val repository = RemoteSyncFeedRepository(source)
        assertIs<AppResult.Failed>(repository.bootstrap("session", company, emptySet(), null))
        assertIs<AppResult.Failed>(repository.changes("session", company, selection, "cursor", 201))
        assertEquals(0, source.calls)
        source.cancel = true
        assertFailsWith<CancellationException> {
            repository.bootstrap("session", company, selection, null)
        }
    }
}

private class FeedSource(var bootstrap: SyncBootstrapDto) : SyncFeedRemoteDataSource {
    var calls = 0
    var cancel = false
    var delta = SyncChangesDto(emptyList(), "next", false, false, 5, "2026-10-11T00:00:00Z")

    override suspend fun bootstrap(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String?,
        limit: Int,
    ): SyncBootstrapDto {
        calls++
        if (cancel) throw CancellationException()
        return bootstrap
    }

    override suspend fun changes(
        sessionId: String,
        companyId: String,
        collections: String,
        cursor: String,
        limit: Int,
    ): SyncChangesDto {
        calls++
        return delta
    }
}
