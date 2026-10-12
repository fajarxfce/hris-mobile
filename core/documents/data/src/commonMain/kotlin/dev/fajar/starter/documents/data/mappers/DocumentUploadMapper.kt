@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun DocumentUpload.toDto(): DocumentUploadDto =
    DocumentUploadDto(
        documentId = intent.documentId,
        revisionId = intent.revisionId,
        employmentId = intent.input.employmentId,
        title = intent.input.title,
        classification = intent.input.classification.name,
        reason = intent.input.reason,
        createdAt = intent.createdAt.toString(),
        step =
            when (val s = step) {
                is DocumentUploadStep.Start -> DocumentUploadStepDto.Start(s.operationId)
                is DocumentUploadStep.Chunk ->
                    DocumentUploadStepDto.Chunk(s.operationId, s.offset, s.version)
                is DocumentUploadStep.Validate ->
                    DocumentUploadStepDto.Validate(s.operationId, s.version)
                is DocumentUploadStep.Processing -> DocumentUploadStepDto.Processing(s.version)
                is DocumentUploadStep.Cancel ->
                    DocumentUploadStepDto.Cancel(s.operationId, s.version, s.reason)
                is DocumentUploadStep.Finished ->
                    DocumentUploadStepDto.Finished(
                        s.revision.let {
                            DocumentProgressDto(
                                it.status.name,
                                it.uploadedBytes,
                                it.version,
                                it.expiresAt.toString(),
                                it.validationAttempts,
                                it.failureCode,
                            )
                        }
                    )
            },
        attempts = attempts,
        nextAttemptAt = nextAttemptAt?.toString(),
        failure =
            failure?.let {
                DocumentUploadFailureDto(
                    it.kind.name,
                    it.code,
                    it.correlationId,
                    it.retryAfterSeconds,
                )
            },
    )

fun DocumentUploadDto.toUpload(size: Long): DocumentUpload {
    require(
        format == 1 &&
            size in 1..DOCUMENT_MAXIMUM_BYTES &&
            attempts in 0..DOCUMENT_UPLOAD_MAXIMUM_ATTEMPTS
    )
    require(
        Uuid.parse(documentId).toString() == documentId &&
            Uuid.parse(revisionId).toString() == revisionId
    )
    require(createdAt.length <= 40 && (nextAttemptAt == null || nextAttemptAt.length <= 40))
    val input =
        DocumentUploadInput(
            employmentId,
            title,
            DocumentClassification.valueOf(classification),
            reason,
        )
    require(validateDocumentUploadInput(input) == AppResult.Success(input))
    val resolved = step.toDomainStep(size)
    val failure =
        failure?.let {
            require(attempts > 0)
            requireCode(it.code, 100)
            requireCode(it.correlationId, 128)
            require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
            Failure(
                FailureKind.valueOf(it.kind),
                "Document delivery could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    return DocumentUpload(
        DocumentUploadIntent(documentId, revisionId, input, Instant.parse(createdAt)),
        resolved,
        attempts,
        nextAttemptAt?.let(Instant::parse),
        failure,
    )
}

private fun requireVersion(version: Long) = require(version in 0..9_007_199_254_740_991L)

private fun requireOperation(id: String) = require(Uuid.parse(id).toString() == id)

private fun requireCode(code: String?, maximum: Int) =
    require(
        code == null ||
            (code.length in 1..maximum && code.all { it.isLetterOrDigit() || it in "_.:-" })
    )

private fun DocumentUploadStepDto.toDomainStep(size: Long): DocumentUploadStep =
    when (val s = this) {
        is DocumentUploadStepDto.Start -> {
            requireOperation(s.operationId)
            DocumentUploadStep.Start(s.operationId)
        }
        is DocumentUploadStepDto.Chunk -> {
            requireOperation(s.operationId)
            requireVersion(s.version)
            require(s.offset in 0 until size && s.offset % DOCUMENT_UPLOAD_CHUNK_BYTES == 0L)
            DocumentUploadStep.Chunk(s.operationId, s.offset, s.version)
        }
        is DocumentUploadStepDto.Validate -> {
            requireOperation(s.operationId)
            requireVersion(s.version)
            DocumentUploadStep.Validate(s.operationId, s.version)
        }
        is DocumentUploadStepDto.Processing -> {
            requireVersion(s.version)
            DocumentUploadStep.Processing(s.version)
        }
        is DocumentUploadStepDto.Cancel -> {
            requireOperation(s.operationId)
            requireVersion(s.version)
            require(s.reason.isNotBlank() && s.reason.length <= 1_000)
            DocumentUploadStep.Cancel(s.operationId, s.version, s.reason)
        }
        is DocumentUploadStepDto.Finished -> {
            val r = s.revision
            requireVersion(r.version)
            require(
                r.uploadedBytes in 0..size &&
                    (r.uploadedBytes == size || r.uploadedBytes % DOCUMENT_UPLOAD_CHUNK_BYTES == 0L)
            )
            require(r.validationAttempts in 0..100 && r.expiresAt.length <= 40)
            requireCode(r.failureCode, 100)
            val status = DocumentRevisionStatus.valueOf(r.status)
            require(
                status !in
                    setOf(DocumentRevisionStatus.UPLOADING, DocumentRevisionStatus.VALIDATING)
            )
            if (
                status in
                    setOf(
                        DocumentRevisionStatus.READY,
                        DocumentRevisionStatus.RETIRED,
                        DocumentRevisionStatus.REJECTED,
                        DocumentRevisionStatus.VALIDATION_FAILED,
                    )
            )
                require(r.uploadedBytes == size)
            DocumentUploadStep.Finished(
                DocumentRevisionProgress(
                    status,
                    r.uploadedBytes,
                    r.version,
                    Instant.parse(r.expiresAt),
                    r.validationAttempts,
                    r.failureCode,
                )
            )
        }
    }
