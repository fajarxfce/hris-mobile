@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.mappers

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
    val items =
        documents.items.map { row ->
            require(Uuid.parse(row.documentId).toString() == row.documentId)
            require(Uuid.parse(row.revisionId).toString() == row.revisionId)
            require(
                row.revisionNumber in 1..100 && row.title.isNotBlank() && row.title.length <= 160
            )
            require(row.fileName.isNotBlank() && row.fileName.length <= 180)
            require(
                row.fileName.none {
                    it.code in 0..31 || it.code in 127..159 || it == '/' || it == '\\'
                }
            )
            val extensions =
                when (row.mediaType) {
                    "application/pdf" -> setOf("pdf")
                    "image/jpeg" -> setOf("jpg", "jpeg")
                    "image/png" -> setOf("png")
                    else -> emptySet()
                }
            require(row.fileName.substringAfterLast('.', "").lowercase() in extensions)
            require(
                row.size in 1..DOCUMENT_MAXIMUM_BYTES && Regex("[0-9a-f]{64}").matches(row.sha256)
            )
            require(row.validatedAt.length <= 40)
            DocumentEvidence(
                row.documentId,
                row.revisionId,
                row.revisionNumber,
                row.title,
                row.fileName,
                row.mediaType,
                row.size,
                row.sha256,
                Instant.parse(row.validatedAt),
            )
        }
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
