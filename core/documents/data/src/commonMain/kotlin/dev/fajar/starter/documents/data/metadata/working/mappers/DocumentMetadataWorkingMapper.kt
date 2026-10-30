package dev.fajar.starter.documents.data.metadata.working.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.data.metadata.mappers.*
import dev.fajar.starter.documents.data.metadata.working.dto.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.metadata.working.policies.validateDocumentMetadataWorkingCopy
import kotlin.time.Instant

fun DocumentMetadataWorkingCopy.toWorkingDto() =
    DocumentMetadataWorkingDto(
        head.toMetadataHeadDto(),
        DocumentMetadataWorkingInputDto(
            input.category.name,
            input.identityNumber,
            input.issuingCountry,
            input.issuedOn,
            input.validUntil,
        ),
        reason,
        updatedAt.toString(),
    )

fun DocumentMetadataWorkingDto.toWorkingCopy(
    generation: String,
    revision: Long,
    documentId: String,
): DocumentMetadataWorkingCopy {
    require(format == 1 && updatedAt.length <= 40)
    val copy =
        DocumentMetadataWorkingCopy(
            generation,
            revision,
            head.toMetadataHead(documentId),
            DocumentMetadataInput(
                DocumentCategory.valueOf(input.category),
                input.identityNumber,
                input.issuingCountry,
                input.issuedOn,
                input.validUntil,
            ),
            reason,
            Instant.parse(updatedAt),
        )
    require(validateDocumentMetadataWorkingCopy(copy) is AppResult.Success)
    return copy
}
