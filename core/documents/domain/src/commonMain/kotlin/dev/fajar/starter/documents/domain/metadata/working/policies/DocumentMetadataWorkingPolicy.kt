@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.working.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataHead
import dev.fajar.starter.documents.domain.metadata.policies.*
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Draft validation bounds storage while preserving unfinished dates and other invalid input. */
fun validateDocumentMetadataWorkingCopy(copy: DocumentMetadataWorkingCopy): AppResult<Unit> {
    val head = copy.head
    if (
        listOf(copy.generation, head.id, head.employmentId, head.createdBy).any {
            Uuid.parseOrNull(it)?.toString() != it
        } ||
            copy.revision !in 0..DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION ||
            head.version !in 0..DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION ||
            !head.metadataSupported ||
            head.metadataVersion == null ||
            head.metadataVersion !in 0..DOCUMENT_METADATA_MAXIMUM_VERSION ||
            head.title.isBlank() ||
            head.title.length > 160 ||
            head.metadata == null ||
            copy.input.identityNumber.length > 240 ||
            copy.input.issuingCountry.length > 8 ||
            copy.input.issuedOn.length > 16 ||
            copy.input.validUntil.length > 16 ||
            copy.reason.length > 1000 ||
            copy.updatedAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L
    )
        return documentFailure("client.document_working_copy_invalid")
    return when (val result = validateDocumentBusinessMetadata(requireNotNull(head.metadata))) {
        is AppResult.Failed -> result
        is AppResult.Success -> AppResult.Success(Unit)
    }
}

fun initialDocumentMetadataWorkingCopy(
    head: DocumentMetadataHead,
    generation: String,
    now: Instant,
): AppResult<DocumentMetadataWorkingCopy> {
    val metadata =
        head.metadata
            ?: return documentFailure(
                "client.document_metadata_unsupported",
                FailureKind.Unavailable,
            )
    val copy =
        DocumentMetadataWorkingCopy(
            generation,
            0,
            head,
            DocumentMetadataInput(
                metadata.category,
                metadata.identityNumber.orEmpty(),
                metadata.issuingCountry.orEmpty(),
                metadata.issuedOn?.toString().orEmpty(),
                metadata.validUntil?.toString().orEmpty(),
            ),
            "",
            now,
        )
    return when (val valid = validateDocumentMetadataWorkingCopy(copy)) {
        is AppResult.Failed -> valid
        is AppResult.Success -> AppResult.Success(copy)
    }
}
