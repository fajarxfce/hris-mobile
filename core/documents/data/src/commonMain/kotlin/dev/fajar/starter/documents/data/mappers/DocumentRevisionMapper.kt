@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_UPLOAD_CHUNK_BYTES
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun DocumentMutationDto.toReceipt(expectedId: String): DocumentMutationReceipt {
    require(id == expectedId && version in 0..9_007_199_254_740_991L)
    return DocumentMutationReceipt(id, version)
}

fun DocumentRevisionDto.toProgress(
    access: DocumentAccess,
    file: LocalDocumentFile,
    intent: DocumentUploadIntent,
): DocumentRevisionProgress {
    val declaration = file.draft.declaration
    require(
        id == intent.revisionId && documentId == intent.documentId && createdBy == access.accountId
    )
    require(
        number == 1 &&
            fileName == declaration.name &&
            mediaType == declaration.mediaType &&
            size == declaration.size &&
            sha256 == file.sha256 &&
            reason == intent.input.reason
    )
    require(
        chunkSize == DOCUMENT_UPLOAD_CHUNK_BYTES &&
            version in 0..9_007_199_254_740_991L &&
            uploadedBytes in 0..size &&
            (uploadedBytes == size || uploadedBytes % chunkSize == 0L)
    )
    require(createdAt.length <= 40 && expiresAt.length <= 40 && validationAttempts in 0..100)
    val created = Instant.parse(createdAt)
    val expiry = Instant.parse(expiresAt)
    require(expiry > created)
    validationJobId?.let { require(Uuid.parse(it).toString() == it) }
    validatedAt?.let { require(it.length <= 40 && Instant.parse(it) >= created) }
    require(failureCode == null || Regex("[A-Za-z0-9_.:-]{1,100}").matches(failureCode))
    val resolved = DocumentRevisionStatus.valueOf(status)
    if (
        resolved in
            setOf(
                DocumentRevisionStatus.VALIDATING,
                DocumentRevisionStatus.VALIDATION_FAILED,
                DocumentRevisionStatus.READY,
                DocumentRevisionStatus.REJECTED,
                DocumentRevisionStatus.RETIRED,
            )
    )
        require(uploadedBytes == size)
    if (resolved == DocumentRevisionStatus.READY || resolved == DocumentRevisionStatus.RETIRED)
        require(validatedAt != null && failureCode == null)
    return DocumentRevisionProgress(
        resolved,
        uploadedBytes,
        version,
        expiry,
        validationAttempts,
        failureCode,
    )
}
