@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.documents.data.dto.DocumentEvidenceDto
import dev.fajar.starter.documents.data.dto.SelfDocumentEvidenceDto
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_MAXIMUM_BYTES
import dev.fajar.starter.documents.domain.policies.DOCUMENT_PAGE_SIZE
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun SelfDocumentEvidenceDto.toEvidencePage(
    access: DocumentAccess,
    query: DocumentEvidenceQuery,
): DocumentEvidencePage {
    require(
        accountId == access.accountId &&
            companyId == access.companyId &&
            employmentId == query.employmentId
    )
    require(
        classification == query.classification.name && documents.items.size <= DOCUMENT_PAGE_SIZE
    )
    val items = documents.items.map(DocumentEvidenceDto::toEvidence)
    require(
        items.distinctBy { it.documentId }.size == items.size &&
            items.distinctBy { it.revisionId }.size == items.size
    )
    require(items.zipWithNext().all { (a, b) -> a.documentId < b.documentId })
    val after = query.after
    require(after == null || items.all { it.documentId > after })
    val cursor = documents.nextCursor
    require(
        cursor == null ||
            (items.size == DOCUMENT_PAGE_SIZE &&
                cursor == items.last().documentId &&
                cursor != query.after)
    )
    return DocumentEvidencePage(items.toList(), cursor)
}

fun DocumentEvidenceDto.toEvidence(): DocumentEvidence {
    require(Uuid.parse(documentId).toString() == documentId)
    require(Uuid.parse(revisionId).toString() == revisionId)
    require(revisionNumber in 1..100 && title.isNotBlank() && title.length <= 160)
    require(fileName.isNotBlank() && fileName.length <= 180)
    require(fileName.none { it.code in 0..31 || it.code in 127..159 || it == '/' || it == '\\' })
    val extensions =
        when (mediaType) {
            "application/pdf" -> setOf("pdf")
            "image/jpeg" -> setOf("jpg", "jpeg")
            "image/png" -> setOf("png")
            else -> emptySet()
        }
    require(fileName.substringAfterLast('.', "").lowercase() in extensions)
    require(size in 1..DOCUMENT_MAXIMUM_BYTES && Regex("[0-9a-f]{64}").matches(sha256))
    require(validatedAt.length <= 40)
    return DocumentEvidence(
        documentId,
        revisionId,
        revisionNumber,
        title,
        fileName,
        mediaType,
        size,
        sha256,
        Instant.parse(validatedAt),
    )
}
