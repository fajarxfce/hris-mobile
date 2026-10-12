package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.api.DocumentsApi
import dev.fajar.starter.documents.data.datasources.*
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.data.repositories.RemoteDocumentRepository
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_UPLOAD_CHUNK_BYTES
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class DocumentUploadTransportTest {
    @Test
    fun ownedDirectoryValidatesEchoesAndCursorProgress() = runTest {
        val f = DocumentDataFixture()
        val repository = RemoteDocumentRepository(f)
        assertEquals(
            f.query.employmentId,
            assertIs<AppResult.Success<DocumentEmploymentPage>>(
                    repository.employments(f.access, null)
                )
                .value
                .items
                .single()
                .id,
        )
        val original = f.directory
        for (bad in
            listOf(
                original.copy(accountId = f.query.employmentId),
                original.copy(companyId = f.query.employmentId),
                original.copy(asOf = "invalid"),
                original.copy(timezone = "Unknown/Place"),
                original.copy(
                    employments =
                        original.employments.copy(
                            items = original.employments.items + original.employments.items
                        )
                ),
                original.copy(employments = original.employments.copy(nextCursor = "different")),
            )) {
            f.directory = bad
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.employments(f.access, null)).failure.kind,
            )
        }
        f.directory = original.copy(employments = original.employments.copy(nextCursor = "EMP-001"))
        assertIs<AppResult.Failed>(repository.employments(f.access, "EMP-001"))
    }

    @Test
    fun mutationsUseAuthenticatedBoundedEndpointsAndStableCommandBodies() = runTest {
        val f = UploadTransportFixture()
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val operation = "77777777-7777-7777-7777-777777777777"
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(f.base.access.sessionId, request.attributes[RequestSession])
                    val endpoint = request.url.encodedPath.substringAfter("/documents/")
                    calls += endpoint
                    if (request.method == HttpMethod.Post)
                        assertEquals(operation, request.headers["Idempotency-Key"])
                    else assertNull(request.headers["Idempotency-Key"])
                    val response =
                        when {
                            endpoint == "uploads" -> {
                                bodies += (request.body as TextContent).text
                                val body =
                                    Json.decodeFromString<DocumentUploadRequestDto>(bodies.last())
                                assertEquals(f.intent.documentId, body.documentId)
                                assertEquals(f.intent.revisionId, body.revisionId)
                                assertEquals(f.intent.input.employmentId, body.employmentId)
                                assertEquals(f.file.sha256, body.sha256)
                                assertEquals(0L, body.expectedDocumentVersion)
                                Json.encodeToString(DocumentMutationDto(f.intent.revisionId, 0))
                            }
                            endpoint.endsWith("/chunks") -> {
                                assertEquals("0", request.headers["Upload-Offset"])
                                assertEquals(
                                    "b".repeat(64),
                                    request.headers["Upload-Checksum-Sha256"],
                                )
                                assertEquals(
                                    ContentType.Application.OctetStream,
                                    request.body.contentType,
                                )
                                assertContentEquals(
                                    byteArrayOf(1, 2, 3),
                                    (request.body as OutgoingContent.ByteArrayContent).bytes(),
                                )
                                Json.encodeToString(DocumentMutationDto(f.intent.revisionId, 1))
                            }
                            endpoint.endsWith("/validate") || endpoint.endsWith("/cancel") -> {
                                val body =
                                    Json.decodeFromString<DocumentVersionRequestDto>(
                                        (request.body as TextContent).text
                                    )
                                assertEquals(1L, body.expectedVersion)
                                assertEquals("Supporting file", body.reason)
                                Json.encodeToString(DocumentMutationDto(f.intent.revisionId, 2))
                            }
                            endpoint == "me/employments" -> {
                                assertEquals("20", request.url.parameters["limit"])
                                Json.encodeToString(f.base.directory)
                            }
                            else -> Json.encodeToString(f.revision)
                        }
                    respond(
                        response,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteDocumentRepository(ApiDocumentDataSource(DocumentsApi(client)))
            repeat(2) {
                assertIs<AppResult.Success<DocumentMutationReceipt>>(
                    repository.start(f.base.access, f.file, f.intent, operation)
                )
            }
            assertEquals(bodies[0], bodies[1])
            assertIs<AppResult.Success<DocumentMutationReceipt>>(
                repository.chunk(
                    f.base.access,
                    f.intent.revisionId,
                    operation,
                    0,
                    DocumentFileChunk(byteArrayOf(1, 2, 3), "b".repeat(64)),
                )
            )
            assertIs<AppResult.Success<DocumentMutationReceipt>>(
                repository.validate(
                    f.base.access,
                    f.intent.revisionId,
                    operation,
                    1,
                    f.intent.input.reason,
                )
            )
            assertIs<AppResult.Success<DocumentMutationReceipt>>(
                repository.cancel(
                    f.base.access,
                    f.intent.revisionId,
                    operation,
                    1,
                    f.intent.input.reason,
                )
            )
            assertIs<AppResult.Success<DocumentRevisionProgress>>(
                repository.revision(f.base.access, f.file, f.intent)
            )
            assertIs<AppResult.Success<DocumentEmploymentPage>>(
                repository.employments(f.base.access, null)
            )
            assertEquals(7, calls.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun mismatchedOrRegressingFileFactsNeverBecomeServerProgress() = runTest {
        val f = UploadTransportFixture()
        var revision = f.revision
        val source =
            object : DocumentRemoteDataSource by f.base {
                override suspend fun revision(
                    sessionId: String,
                    companyId: String,
                    revisionId: String,
                ) = revision
            }
        val repository = RemoteDocumentRepository(source)
        val valid = f.revision
        for (bad in
            listOf(
                valid.copy(id = f.intent.documentId),
                valid.copy(documentId = f.base.query.employmentId),
                valid.copy(createdBy = f.base.query.employmentId),
                valid.copy(sha256 = "b".repeat(64)),
                valid.copy(fileName = "other.pdf"),
                valid.copy(size = 99),
                valid.copy(reason = "Other"),
                valid.copy(chunkSize = 100),
                valid.copy(uploadedBytes = 1),
                valid.copy(status = "UNKNOWN"),
                valid.copy(expiresAt = valid.createdAt),
                valid.copy(validatedAt = null),
                valid.copy(failureCode = "unexpected code with secrets"),
            )) {
            revision = bad
            assertEquals(
                FailureKind.Unexpected,
                assertIs<AppResult.Failed>(repository.revision(f.base.access, f.file, f.intent))
                    .failure
                    .kind,
            )
        }
        revision = valid
        assertEquals(
            DocumentRevisionStatus.READY,
            assertIs<AppResult.Success<DocumentRevisionProgress>>(
                    repository.revision(f.base.access, f.file, f.intent)
                )
                .value
                .status,
        )
    }

    @Test
    fun cancellationAndLateAcquisitionCannotBecomeAReceipt() = runTest {
        val f = UploadTransportFixture()
        val pending = CompletableDeferred<Unit>()
        val source =
            object : DocumentRemoteDataSource by f.base {
                override suspend fun start(
                    sessionId: String,
                    companyId: String,
                    operationId: String,
                    request: DocumentUploadRequestDto,
                ): DocumentMutationDto {
                    pending.complete(Unit)
                    awaitCancellation()
                }
            }
        val repository = RemoteDocumentRepository(source)
        val job = async {
            repository.start(
                f.base.access,
                f.file,
                f.intent,
                "77777777-7777-7777-7777-777777777777",
            )
        }
        pending.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }
}

private class UploadTransportFixture {
    val base = DocumentDataFixture()
    val file =
        LocalDocumentFile(
            DocumentFileDraft(
                "66666666-6666-6666-6666-666666666666",
                DocumentFileDeclaration("document.pdf", "application/pdf", 3),
                base.now,
            ),
            3,
            "a".repeat(64),
            2,
        )
    val intent =
        DocumentUploadIntent(
            base.item.documentId,
            base.item.revisionId,
            DocumentUploadInput(
                base.query.employmentId,
                "Personal document",
                DocumentClassification.PERSONAL,
                "Supporting file",
            ),
            base.now,
        )
    val revision =
        DocumentRevisionDto(
            intent.revisionId,
            intent.documentId,
            1,
            file.draft.declaration.name,
            "application/pdf",
            3,
            "a".repeat(64),
            "READY",
            3,
            DOCUMENT_UPLOAD_CHUNK_BYTES,
            3,
            base.access.accountId,
            base.now.toString(),
            (base.now + 24.hours).toString(),
            intent.input.reason,
            null,
            1,
            null,
            base.now.toString(),
        )
}
