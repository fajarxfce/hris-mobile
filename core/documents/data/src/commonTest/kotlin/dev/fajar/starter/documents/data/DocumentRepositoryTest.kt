package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.api.DocumentsApi
import dev.fajar.starter.documents.data.datasources.ApiDocumentDataSource
import dev.fajar.starter.documents.data.dto.DocumentEvidencePageDto
import dev.fajar.starter.documents.data.repositories.RemoteDocumentRepository
import dev.fajar.starter.documents.domain.entities.DocumentEvidencePage
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentRepositoryTest {
    @Test
    fun catalogUsesTheSessionInterceptorAndBoundedOwnedReadWithoutCommandHeaders() = runTest {
        val f = DocumentDataFixture()
        val after = "00000000-0000-0000-0000-000000000001"
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/documents/me/evidence",
                        request.url.encodedPath,
                    )
                    assertEquals(f.query.employmentId, request.url.parameters["employmentId"])
                    assertEquals("PERSONAL", request.url.parameters["classification"])
                    assertEquals("20", request.url.parameters["limit"])
                    assertEquals(after, request.url.parameters["after"])
                    assertEquals(HttpMethod.Get, request.method)
                    assertNull(request.headers["Idempotency-Key"])
                    respond(
                        Json.encodeToString(f.response),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteDocumentRepository(ApiDocumentDataSource(DocumentsApi(client)))
            val page =
                assertIs<AppResult.Success<DocumentEvidencePage>>(
                        repository.evidence(f.access, f.query.copy(after = after))
                    )
                    .value
            assertEquals(f.item.revisionId, page.items.single().revisionId)
            assertEquals(f.item.sha256, page.items.single().sha256)
            assertEquals(f.now, page.items.single().validatedAt)
            assertNull(page.nextCursor)
        } finally {
            client.close()
        }
    }

    @Test
    fun wrongOwnerClassificationOrFileFactsCannotEscapeTheSafeBoundary() = runTest {
        val f = DocumentDataFixture()
        val repository = RemoteDocumentRepository(f)
        val original = f.response
        val invalidRows =
            listOf(
                f.item.copy(documentId = "../other"),
                f.item.copy(revisionId = "invalid"),
                f.item.copy(revisionNumber = 0),
                f.item.copy(revisionNumber = 101),
                f.item.copy(title = ""),
                f.item.copy(title = "a".repeat(161)),
                f.item.copy(fileName = "../file.pdf"),
                f.item.copy(fileName = "file\n.pdf"),
                f.item.copy(fileName = "image.jpg"),
                f.item.copy(mediaType = "text/html"),
                f.item.copy(size = 0),
                f.item.copy(size = 104_857_601L),
                f.item.copy(sha256 = "A".repeat(64)),
                f.item.copy(validatedAt = "not-a-time"),
            )
        val invalid =
            listOf(
                original.copy(accountId = f.query.employmentId),
                original.copy(companyId = f.query.employmentId),
                original.copy(employmentId = f.access.accountId),
                original.copy(classification = "HR_ONLY"),
            ) +
                invalidRows.map {
                    original.copy(documents = DocumentEvidencePageDto(listOf(it), null))
                }
        for (response in invalid) {
            f.response = response
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.evidence(f.access, f.query)).failure.kind,
            )
        }
        f.response = original
        assertIs<AppResult.Success<DocumentEvidencePage>>(repository.evidence(f.access, f.query))
    }

    @Test
    fun finitePagesRejectDuplicatesReversedOrderingAndNonadvancingCursors() = runTest {
        val f = DocumentDataFixture()
        val repository = RemoteDocumentRepository(f)
        val items =
            List(20) { index ->
                val suffix = (index + 1).toString().padStart(12, '0')
                f.item.copy(
                    documentId = "00000000-0000-0000-0000-$suffix",
                    revisionId = "10000000-0000-0000-0000-$suffix",
                )
            }
        val cursor = items.last().documentId
        f.response = f.response.copy(documents = DocumentEvidencePageDto(items, cursor))
        assertEquals(
            cursor,
            assertIs<AppResult.Success<DocumentEvidencePage>>(
                    repository.evidence(f.access, f.query)
                )
                .value
                .nextCursor,
        )
        val invalid =
            listOf(
                DocumentEvidencePageDto(items + items.last(), null),
                DocumentEvidencePageDto(items.reversed(), null),
                DocumentEvidencePageDto(listOf(items.first(), items.first()), null),
                DocumentEvidencePageDto(items.take(1), cursor),
                DocumentEvidencePageDto(items, f.item.documentId),
                DocumentEvidencePageDto(emptyList(), cursor),
            )
        for (page in invalid) {
            f.response = f.response.copy(documents = page)
            assertIs<AppResult.Failed>(repository.evidence(f.access, f.query))
        }
        f.response = f.response.copy(documents = DocumentEvidencePageDto(items, cursor))
        assertIs<AppResult.Failed>(repository.evidence(f.access, f.query.copy(after = cursor)))
    }

    @Test
    fun excessiveBodiesAreRejectedAndApiFailuresKeepCodesWithoutServerProse() = runTest {
        val f = DocumentDataFixture()
        var oversized = true
        val client =
            createHttpClient(
                MockEngine {
                    if (oversized)
                        respond(
                            Json.encodeToString(
                                f.response.copy(
                                    documents =
                                        DocumentEvidencePageDto(
                                            listOf(f.item.copy(title = "a".repeat(65_536))),
                                            null,
                                        )
                                )
                            ),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    else
                        respond(
                            """{"code":"mfa_required","detail":"server-note-must-not-be-displayed"}""",
                            HttpStatusCode.Forbidden,
                            headersOf(HttpHeaders.ContentType, "application/problem+json"),
                        )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteDocumentRepository(ApiDocumentDataSource(DocumentsApi(client)))
            assertIs<AppResult.Failed>(repository.evidence(f.access, f.query))
            oversized = false
            val failure = assertIs<AppResult.Failed>(repository.evidence(f.access, f.query)).failure
            assertEquals("mfa_required", failure.code)
            assertFalse(failure.message.contains("server-note"))
        } finally {
            client.close()
        }
    }

    @Test
    fun cancelledAcquisitionCannotReturnALateMappedPage() = runTest {
        val f = DocumentDataFixture()
        val release = CompletableDeferred<Unit>()
        f.acquire = {
            withContext(NonCancellable) { release.await() }
            f.response
        }
        val repository = RemoteDocumentRepository(f)
        val pending = async { repository.evidence(f.access, f.query) }
        runCurrent()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(1, f.calls)
    }
}
