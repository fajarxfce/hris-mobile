package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.validateDocumentDownload
import kotlin.time.Instant

fun DocumentDownloadSource.toDto(): DocumentDownloadSourceDto {
    require(validateDocumentDownload(this) is AppResult.Success)
    return DocumentDownloadSourceDto(
        employmentId,
        classification.name,
        DocumentEvidenceDto(
            evidence.documentId,
            evidence.revisionId,
            evidence.revisionNumber,
            evidence.title,
            evidence.fileName,
            evidence.mediaType,
            evidence.size,
            evidence.sha256,
            evidence.validatedAt.toString(),
        ),
    )
}

fun DocumentDownloadSourceDto.toDownloadSource(): DocumentDownloadSource {
    require(evidence.validatedAt.length <= 40)
    return DocumentDownloadSource(
            employmentId,
            DocumentClassification.valueOf(classification),
            DocumentEvidence(
                evidence.documentId,
                evidence.revisionId,
                evidence.revisionNumber,
                evidence.title,
                evidence.fileName,
                evidence.mediaType,
                evidence.size,
                evidence.sha256,
                Instant.parse(evidence.validatedAt),
            ),
        )
        .also { require(validateDocumentDownload(it) is AppResult.Success) }
}
