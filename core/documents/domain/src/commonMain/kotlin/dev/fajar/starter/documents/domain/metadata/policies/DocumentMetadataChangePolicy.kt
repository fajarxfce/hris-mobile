@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import dev.fajar.starter.documents.domain.policies.*
import kotlin.uuid.Uuid

const val DOCUMENT_METADATA_PAGE_SIZE = 20
const val DOCUMENT_METADATA_MAXIMUM_VERSION = 999L
const val DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION = 9_007_199_254_740_991L

fun validateDocumentMetadataChange(
    change: DocumentMetadataChange
): AppResult<DocumentMetadataChange> {
    if (
        Uuid.parseOrNull(change.operationId)?.toString() != change.operationId ||
            Uuid.parseOrNull(change.documentId)?.toString() != change.documentId ||
            change.expectedVersion !in 0 until DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION ||
            change.reason.isBlank() ||
            change.reason.length > 1_000 ||
            change.reason.any { it.code in 0..31 || it.code in 127..159 }
    )
        return documentFailure("client.document_metadata_change_invalid")
    val valid = validateDocumentBusinessMetadata(change.metadata)
    return when (valid) {
        is AppResult.Failed -> valid
        is AppResult.Success -> AppResult.Success(change)
    }
}
