package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*

/** Reconciliation can advance known progress, but cannot rewrite an uncertain mutation. */
fun resolveDocumentUploadReview(
    upload: DocumentUpload,
    progress: DocumentRevisionProgress,
    size: Long,
    action: DocumentUploadAction,
    operationId: String,
): AppResult<DocumentUpload> {
    val previous = upload.step
    val version =
        when (previous) {
            is DocumentUploadStep.Start -> 0
            is DocumentUploadStep.Chunk -> previous.version
            is DocumentUploadStep.Validate -> previous.version
            is DocumentUploadStep.Cancel -> previous.version
            is DocumentUploadStep.Processing -> previous.version
            is DocumentUploadStep.Finished -> previous.revision.version
        }
    val offset =
        when (previous) {
            is DocumentUploadStep.Chunk -> previous.offset
            is DocumentUploadStep.Validate -> size
            is DocumentUploadStep.Finished -> previous.revision.uploadedBytes
            else -> 0
        }
    if (progress.version < version || progress.uploadedBytes < offset)
        return documentFailure("client.invalid_response", FailureKind.Unexpected)
    val terminal =
        progress.status !in
            setOf(
                DocumentRevisionStatus.UPLOADING,
                DocumentRevisionStatus.VALIDATING,
                DocumentRevisionStatus.VALIDATION_FAILED,
            )
    if (terminal)
        return AppResult.Success(
            upload.copy(
                step = DocumentUploadStep.Finished(progress),
                attempts = 0,
                nextAttemptAt = null,
                failure = null,
            )
        )
    if (previous is DocumentUploadStep.Cancel && action == DocumentUploadAction.CheckStatus) {
        val cancellation =
            if (progress.version == previous.version) previous
            else DocumentUploadStep.Cancel(operationId, progress.version, previous.reason)
        return AppResult.Success(
            upload.copy(
                step = cancellation,
                nextAttemptAt = null,
                attempts = if (cancellation == previous) upload.attempts else 0,
                failure = if (cancellation == previous) upload.failure else null,
            )
        )
    }
    if (previous is DocumentUploadStep.Cancel && action == DocumentUploadAction.RetryValidation)
        return documentFailure("client.document_upload_active", FailureKind.Conflict)
    val step =
        when (val result = reviewedUploadStep(upload, progress, size, action, operationId)) {
            is AppResult.Failed -> return result
            is AppResult.Success -> result.value
        }
    val pendingMutation =
        step == previous &&
            step !is DocumentUploadStep.Processing &&
            step !is DocumentUploadStep.Finished
    return AppResult.Success(
        upload.copy(
            step = step,
            nextAttemptAt = null,
            attempts = if (pendingMutation) upload.attempts else 0,
            failure = if (pendingMutation) upload.failure else null,
        )
    )
}

private fun reviewedUploadStep(
    upload: DocumentUpload,
    progress: DocumentRevisionProgress,
    size: Long,
    action: DocumentUploadAction,
    operationId: String,
): AppResult<DocumentUploadStep> {
    val previous = upload.step
    val step =
        when (action) {
            is DocumentUploadAction.Cancel -> {
                val normalized =
                    when (
                        val result =
                            validateDocumentUploadInput(
                                upload.intent.input.copy(reason = action.reason)
                            )
                    ) {
                        is AppResult.Failed -> return result
                        is AppResult.Success -> result.value.reason
                    }
                if (previous is DocumentUploadStep.Cancel && progress.version == previous.version) {
                    if (normalized != previous.reason)
                        return documentFailure(
                            "client.document_upload_active",
                            FailureKind.Conflict,
                        )
                    previous
                } else DocumentUploadStep.Cancel(operationId, progress.version, normalized)
            }
            DocumentUploadAction.RetryValidation -> {
                if (progress.status != DocumentRevisionStatus.VALIDATION_FAILED)
                    return documentFailure(
                        "client.document_validation_unavailable",
                        FailureKind.Conflict,
                    )
                DocumentUploadStep.Validate(operationId, progress.version)
            }
            DocumentUploadAction.CheckStatus ->
                when (progress.status) {
                    DocumentRevisionStatus.VALIDATION_FAILED ->
                        DocumentUploadStep.Finished(progress)
                    DocumentRevisionStatus.VALIDATING -> {
                        if (
                            previous is DocumentUploadStep.Cancel &&
                                progress.version == previous.version
                        )
                            previous
                        else DocumentUploadStep.Processing(progress.version)
                    }
                    DocumentRevisionStatus.UPLOADING ->
                        when {
                            previous is DocumentUploadStep.Processing ||
                                previous is DocumentUploadStep.Finished ->
                                return documentFailure(
                                    "client.invalid_response",
                                    FailureKind.Unexpected,
                                )
                            previous is DocumentUploadStep.Cancel &&
                                progress.version == previous.version -> previous
                            previous is DocumentUploadStep.Validate &&
                                progress.version == previous.version -> previous
                            previous is DocumentUploadStep.Chunk &&
                                progress.version == previous.version &&
                                progress.uploadedBytes == previous.offset -> previous
                            progress.uploadedBytes == size ->
                                DocumentUploadStep.Validate(operationId, progress.version)
                            else ->
                                DocumentUploadStep.Chunk(
                                    operationId,
                                    progress.uploadedBytes,
                                    progress.version,
                                )
                        }
                    else -> error("Terminal revisions were resolved above")
                }
        }
    return AppResult.Success(step)
}
