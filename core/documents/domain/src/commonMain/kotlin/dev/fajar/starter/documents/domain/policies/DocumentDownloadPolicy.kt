@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.*
import kotlin.uuid.Uuid

const val DOCUMENT_DOWNLOAD_CHUNK_BYTES = 1_048_576

fun validateDocumentDownload(source: DocumentDownloadSource): AppResult<Unit> {
    val evidence = source.evidence
    if (
        Uuid.parseOrNull(source.employmentId)?.toString() != source.employmentId ||
            Uuid.parseOrNull(evidence.documentId)?.toString() != evidence.documentId ||
            Uuid.parseOrNull(evidence.revisionId)?.toString() != evidence.revisionId ||
            evidence.revisionNumber !in 1..100 ||
            evidence.title.isBlank() ||
            evidence.title.length > 160 ||
            !Regex("[0-9a-f]{64}").matches(evidence.sha256)
    )
        return documentFailure("client.document_selection_invalid")
    return when (
        val result =
            resolveDocumentFile(
                DocumentFileSelection(evidence.fileName, evidence.mediaType, evidence.size)
            )
    ) {
        is AppResult.Failed -> result
        is AppResult.Success -> AppResult.Success(Unit)
    }
}
