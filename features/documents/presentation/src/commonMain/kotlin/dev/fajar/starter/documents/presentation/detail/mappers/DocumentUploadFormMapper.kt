package dev.fajar.starter.documents.presentation.detail.mappers

import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.presentation.detail.DocumentUploadForm

fun createDocumentUploadForm(
    prepared: DocumentUploadPreparation,
    previous: DocumentUploadForm?,
): DocumentUploadForm {
    val original = prepared.file.upload?.intent?.input
    val selected = previous?.employmentId ?: original?.employmentId
    val employment =
        selected?.takeIf { id -> prepared.employments.items.any { it.id == id } }
            ?: prepared.employments.items.singleOrNull()?.id
    return DocumentUploadForm(
        prepared,
        employment,
        previous?.title
            ?: original?.title
            ?: prepared.file.draft.declaration.name.substringBeforeLast('.').take(160),
        previous?.reason ?: original?.reason ?: "",
        previous?.classification ?: original?.classification ?: DocumentClassification.PERSONAL,
        previous?.metadata
            ?: original?.metadata?.let {
                DocumentMetadataInput(
                    it.category,
                    it.identityNumber.orEmpty(),
                    it.issuingCountry.orEmpty(),
                    it.issuedOn?.toString().orEmpty(),
                    it.validUntil?.toString().orEmpty(),
                )
            }
            ?: DocumentMetadataInput(),
    )
}
