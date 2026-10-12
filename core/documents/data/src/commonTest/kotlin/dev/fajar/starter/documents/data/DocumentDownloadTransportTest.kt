package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.api.DocumentsApi
import dev.fajar.starter.documents.data.datasources.*
import dev.fajar.starter.documents.data.dto.DocumentContentDto
import dev.fajar.starter.documents.data.mappers.toEvidencePage
import dev.fajar.starter.documents.data.repositories.RemoteDocumentContentRepository
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DocumentDownloadTransportTest {
    @Test
    fun contentUsesCurrentSessionAndImmutableRevisionRanges() = runTest {
        val f = DocumentDataFixture()
        val evidence =
            f.response.toEvidencePage(f.access, f.query).items.single().copy(size = 65_540)
        val tag = "\"${evidence.revisionId}-${evidence.sha256}\""
        val calls = mutableListOf<HttpMethod>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/documents/revisions/${evidence.revisionId}/content",
                        request.url.encodedPath,
                    )
                    calls += request.method
                    if (request.method == HttpMethod.Head) {
                        assertNull(request.headers[HttpHeaders.Range])
                        respond(
                            "",
                            HttpStatusCode.OK,
                            headersOf(
                                HttpHeaders.ETag to listOf(tag),
                                HttpHeaders.ContentLength to listOf(evidence.size.toString()),
                                HttpHeaders.ContentType to listOf(evidence.mediaType),
                            ),
                        )
                    } else {
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals(tag, request.headers[HttpHeaders.IfRange])
                        assertNull(request.headers[HttpHeaders.IfMatch])
                        assertEquals("bytes=65536-65539", request.headers[HttpHeaders.Range])
                        assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
                        respond(
                            byteArrayOf(1, 2, 3, 4),
                            HttpStatusCode.PartialContent,
                            headersOf(
                                HttpHeaders.ETag to listOf(tag),
                                HttpHeaders.ContentLength to listOf("4"),
                                HttpHeaders.ContentRange to listOf("bytes 65536-65539/65540"),
                            ),
                        )
                    }
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository =
                RemoteDocumentContentRepository(ApiDocumentDataSource(DocumentsApi(client)))
            assertEquals(AppResult.Success(Unit), repository.inspect(f.access, evidence))
            val bytes =
                assertIs<AppResult.Success<ByteArray>>(repository.chunk(f.access, evidence, 65_536))
                    .value
            assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes)
            bytes.fill(0)
            assertEquals(listOf(HttpMethod.Head, HttpMethod.Get), calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun metadataRejectsChangedAmbiguousOrCompressedDeclarations() = runTest {
        val f = ContentFixture()
        val original = f.metadata
        for (invalid in
            listOf(
                original.copy(entityTags = emptyList()),
                original.copy(entityTags = original.entityTags + original.entityTags),
                original.copy(entityTags = listOf("\"other-revision\"")),
                original.copy(entityTags = listOf("W/${original.entityTags.single()}")),
                original.copy(lengths = listOf("101")),
                original.copy(lengths = listOf("100", "100")),
                original.copy(mediaTypes = listOf("image/png")),
                original.copy(mediaTypes = listOf("application/pdf", "application/pdf")),
                original.copy(encodings = listOf("gzip")),
            )) {
            f.metadata = invalid
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(f.repository.inspect(f.base.access, f.evidence))
                    .failure
                    .kind,
            )
        }
        f.metadata = original
        assertEquals(AppResult.Success(Unit), f.repository.inspect(f.base.access, f.evidence))
        assertEquals(0, f.reads)
    }

    @Test
    fun lateCancelledOrInvalidBuffersAreReleasedBeforePublication() = runTest {
        val f = ContentFixture()
        val buffer = ByteArray(100) { 42 }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.read = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            buffer
        }
        val request = async { f.repository.chunk(f.base.access, f.evidence, 0) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { request.await() }
        request.join()
        assertTrue(buffer.all { it == 0.toByte() })
        val invalid = ByteArray(101) { 23 }
        f.read = { invalid }
        assertIs<AppResult.Failed>(f.repository.chunk(f.base.access, f.evidence, 0))
        assertTrue(invalid.all { it == 0.toByte() })
    }

    @Test
    fun protectedContentFailuresRetainTheirDomainClassification() = runTest {
        val f = ContentFixture()
        f.read = { throw ApiResponseException(403, """{"code":"mfa_required"}""", null, null) }
        val failed = assertIs<AppResult.Failed>(f.repository.chunk(f.base.access, f.evidence, 0))
        assertEquals("mfa_required", failed.failure.code)
        assertEquals(FailureKind.AccessDenied, failed.failure.kind)
    }
}

private class ContentFixture : DocumentContentDataSource {
    val base = DocumentDataFixture()
    val evidence = base.response.toEvidencePage(base.access, base.query).items.single()
    var metadata =
        DocumentContentDto(
            listOf("\"${evidence.revisionId}-${evidence.sha256}\""),
            listOf("100"),
            listOf("application/pdf"),
            emptyList(),
        )
    var reads = 0
    var read: suspend () -> ByteArray = { ByteArray(100) }
    val repository = RemoteDocumentContentRepository(this)

    override suspend fun inspect(sessionId: String, companyId: String, revisionId: String) =
        metadata

    override suspend fun download(
        sessionId: String,
        companyId: String,
        revisionId: String,
        entityTag: String,
        size: Long,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray {
        reads++
        return read()
    }
}
