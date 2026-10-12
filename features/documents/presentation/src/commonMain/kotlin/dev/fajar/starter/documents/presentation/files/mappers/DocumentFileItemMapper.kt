package dev.fajar.starter.documents.presentation.files.mappers

import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.canRemoveDocumentFile
import dev.fajar.starter.documents.presentation.files.DocumentFileItem
import dev.fajar.starter.localization.AppString

fun LocalDocumentFile.toFileItem(): DocumentFileItem {
    val current = upload
    val step = current?.step
    val status =
        when {
            sha256 == null -> AppString.DocumentImportIncomplete
            current == null -> AppString.DocumentNotUploaded
            current.failure != null -> AppString.DocumentAttention
            else ->
                when (step) {
                    is DocumentUploadStep.Start -> AppString.DocumentQueued
                    is DocumentUploadStep.Chunk -> AppString.DocumentUploading
                    is DocumentUploadStep.Validate,
                    is DocumentUploadStep.Processing -> AppString.DocumentChecking
                    is DocumentUploadStep.Cancel -> AppString.DocumentCancelling
                    is DocumentUploadStep.Finished ->
                        when (step.revision.status) {
                            DocumentRevisionStatus.READY -> AppString.DocumentReady
                            DocumentRevisionStatus.REJECTED -> AppString.DocumentRejected
                            DocumentRevisionStatus.VALIDATION_FAILED ->
                                AppString.DocumentValidationFailed
                            DocumentRevisionStatus.CANCELLED -> AppString.DocumentCancelled
                            DocumentRevisionStatus.EXPIRED -> AppString.DocumentExpired
                            DocumentRevisionStatus.RETIRED -> AppString.DocumentRetired
                            else -> AppString.DocumentChecking
                        }
                    null -> AppString.DocumentNotUploaded
                }
        }
    val size = draft.declaration.size
    val sizeText =
        when {
            size >= 1_048_576 -> "${(size * 10 / 1_048_576) / 10f} MB"
            size >= 1_024 -> "${(size + 1_023) / 1_024} KB"
            else -> "$size B"
        }
    val type =
        when (draft.declaration.mediaType) {
            "application/pdf" -> "PDF"
            "image/jpeg" -> "JPG"
            "image/png" -> "PNG"
            else -> ""
        }
    return DocumentFileItem(
        draft.id,
        draft.declaration.name,
        "$type · $sizeText",
        status,
        (step as? DocumentUploadStep.Chunk)?.let {
            (it.offset.toDouble() / size).toFloat().coerceIn(0f, 1f)
        },
        sha256 != null,
        canRemoveDocumentFile(this),
    )
}
