package dev.fajar.starter.documents.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.api.DocumentsApi
import dev.fajar.starter.documents.data.datasources.ApiDocumentDataSource
import dev.fajar.starter.documents.data.repositories.RemoteDocumentRepository
import dev.fajar.starter.documents.domain.replica.entities.DocumentCatalogRecord
import dev.fajar.starter.network.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class DocumentRecordTransportTest {
    @Test
    fun canonicalRecordUsesSessionScopeAndBoundedSafeMapping() = runTest {
        val f = DocumentDataFixture()
        val row = f.recordCall()
        var response = 0
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(f.access.sessionId, request.attributes[RequestSession])
                    assertEquals(
                        "/api/v1/companies/${f.access.companyId}/documents/me/evidence/${row.id}",
                        request.url.encodedPath,
                    )
                    assertEquals(HttpMethod.Get, request.method)
                    assertNull(request.headers["Idempotency-Key"])
                    when (response) {
                        0 ->
                            respond(
                                Json.encodeToString(row),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        1 ->
                            respond(
                                Json.encodeToString(
                                    row.copy(
                                        evidence = row.evidence.copy(title = "x".repeat(16_384))
                                    )
                                ),
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        else ->
                            respond(
                                """{"code":"mfa_required","detail":"Private server diagnostic"}""",
                                HttpStatusCode.Forbidden,
                                headersOf(HttpHeaders.ContentType, "application/problem+json"),
                            )
                    }
                },
                "https://hris.example.test/api/v1/",
            )
        try {
            val repository = RemoteDocumentRepository(ApiDocumentDataSource(DocumentsApi(client)))
            val record =
                assertIs<AppResult.Success<DocumentCatalogRecord>>(
                        repository.record(f.access, row.id)
                    )
                    .value
            assertEquals(row.employmentId, record.employmentId)
            assertEquals(row.version, record.version)
            assertEquals(row.evidence.sha256, record.evidence.sha256)
            response = 1
            assertIs<AppResult.Failed>(repository.record(f.access, row.id))
            response = 2
            val denied = assertIs<AppResult.Failed>(repository.record(f.access, row.id)).failure
            assertEquals("mfa_required", denied.code)
            assertEquals(FailureKind.AccessDenied, denied.kind)
            assertFalse(denied.message.contains("Private server diagnostic"))
        } finally {
            client.close()
        }
    }

    @Test
    fun privateClassificationAndMismatchedReferencesCannotBeMapped() = runTest {
        val f = DocumentDataFixture()
        val record = f.recordCall()
        val repository = RemoteDocumentRepository(f)
        for (invalid in
            listOf(
                record.copy(id = f.query.employmentId),
                record.copy(classification = "HR_ONLY"),
                record.copy(version = -1),
                record.copy(evidence = record.evidence.copy(documentId = f.query.employmentId)),
            )) {
            f.recordCall = { invalid }
            assertIs<AppResult.Failed>(repository.record(f.access, record.id))
        }
    }
}
