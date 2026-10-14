package dev.fajar.starter.documents.data

import dev.fajar.starter.documents.data.datasources.DocumentRemoteDataSource
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.domain.entities.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class DocumentDataFixture : DocumentRemoteDataSource {
    val now = Instant.parse("2026-10-12T01:00:00Z")
    val access =
        DocumentAccess(
            "session",
            "11111111-1111-1111-1111-111111111111",
            "https://hris.example.test/",
            "22222222-2222-2222-2222-222222222222",
            setOf("documents.self.read"),
            now + 1.hours,
            now + 1.hours,
            "client.workspace_expired",
        )
    val query =
        DocumentEvidenceQuery(
            "33333333-3333-3333-3333-333333333333",
            DocumentClassification.PERSONAL,
        )
    val item =
        DocumentEvidenceDto(
            "44444444-4444-4444-4444-444444444444",
            "55555555-5555-5555-5555-555555555555",
            1,
            "Evidence",
            "evidence.pdf",
            "application/pdf",
            100,
            "a".repeat(64),
            now.toString(),
        )
    var recordCall: suspend () -> DocumentCatalogRecordDto = {
        DocumentCatalogRecordDto(
            item.documentId,
            0,
            query.employmentId,
            query.classification.name,
            item,
        )
    }

    override suspend fun record(sessionId: String, companyId: String, id: String) = recordCall()

    var response =
        SelfDocumentEvidenceDto(
            access.accountId,
            access.companyId,
            query.employmentId,
            query.classification.name,
            DocumentEvidencePageDto(listOf(item), null),
        )
    var acquire: suspend () -> SelfDocumentEvidenceDto = { response }
    var calls = 0
    var directory =
        DocumentDirectoryDto(
            access.accountId,
            access.companyId,
            "2026-10-12",
            "Asia/Jakarta",
            DocumentEmploymentPageDto(
                listOf(DocumentEmploymentDto(query.employmentId, "EMP-001", "ACTIVE", 0)),
                null,
            ),
        )

    override suspend fun employments(sessionId: String, companyId: String, after: String?) =
        directory

    override suspend fun start(
        sessionId: String,
        companyId: String,
        operationId: String,
        request: DocumentUploadRequestDto,
    ): DocumentMutationDto = error("Not uploading")

    override suspend fun chunk(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        offset: Long,
        sha256: String,
        bytes: ByteArray,
    ): DocumentMutationDto = error("Not uploading")

    override suspend fun validate(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ): DocumentMutationDto = error("Not uploading")

    override suspend fun cancel(
        sessionId: String,
        companyId: String,
        revisionId: String,
        operationId: String,
        request: DocumentVersionRequestDto,
    ): DocumentMutationDto = error("Not uploading")

    override suspend fun revision(
        sessionId: String,
        companyId: String,
        revisionId: String,
    ): DocumentRevisionDto = error("Not uploading")

    override suspend fun evidence(
        sessionId: String,
        companyId: String,
        employmentId: String,
        classification: String,
        after: String?,
    ): SelfDocumentEvidenceDto {
        calls++
        return acquire()
    }
}
