@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyPolicySnapshot
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

const val DOCUMENT_UPLOAD_CHUNK_BYTES = 1_048_576
const val DOCUMENT_UPLOAD_MAXIMUM_ATTEMPTS = 8
const val DOCUMENT_UPLOAD_AUTOMATIC_ATTEMPTS = 4
const val DOCUMENT_UPLOAD_TASK = "documents.upload"

fun isDocumentUploadRetryable(failure: Failure): Boolean =
    failure.kind in setOf(FailureKind.Network, FailureKind.Timeout, FailureKind.Service) ||
        failure.code == "document_chunk_in_progress"

fun documentUploadRetryAt(now: Instant, failure: Failure, policy: CompanyPolicySnapshot): Instant {
    val delay = (failure.retryAfterSeconds ?: 30).coerceIn(30, 86_400).seconds
    val retryAt =
        failure.parameters["retryAt"]
            ?.takeIf { it.length <= 40 }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.let { policy.requestedAt + (it - policy.policy.serverTime) }
            ?.takeIf { it in now..(now + 86_400.seconds) }
    return maxOf(now + delay, retryAt ?: now)
}

fun prepareDocumentUploadInput(
    input: DocumentUploadInput,
    businessDetails: DocumentMetadataInput?,
): AppResult<DocumentUploadInput> {
    if (businessDetails == null) return validateDocumentUploadInput(input)
    if (input.metadata != null) return documentFailure("client.document_metadata_invalid")
    return when (val metadata = prepareDocumentBusinessMetadata(businessDetails)) {
        is AppResult.Failed -> metadata
        is AppResult.Success -> validateDocumentUploadInput(input.copy(metadata = metadata.value))
    }
}

fun validateDocumentUploadInput(input: DocumentUploadInput): AppResult<DocumentUploadInput> {
    input.metadata?.let {
        val valid = validateDocumentBusinessMetadata(it)
        if (valid is AppResult.Failed) return valid
    }
    if (Uuid.parseOrNull(input.employmentId)?.toString() != input.employmentId)
        return documentFailure("client.document_employment_required")
    val title = input.title.trim()
    val reason = input.reason.trim()
    if (
        title.isEmpty() ||
            title.length > 160 ||
            title.any { it.code in 0..31 || it.code in 127..159 }
    )
        return documentFailure("client.document_title_invalid")
    if (
        reason.isEmpty() ||
            reason.length > 1_000 ||
            reason.any { (it.code in 0..31 && it != '\n' && it != '\t') || it.code in 127..159 }
    )
        return documentFailure("client.document_reason_invalid")
    return AppResult.Success(input.copy(title = title, reason = reason))
}

fun validatePreparedDocumentFile(
    expected: LocalDocumentFile,
    current: LocalDocumentFile,
): AppResult<Unit> =
    if (
        current.draft != expected.draft ||
            current.draft.download != null ||
            current.sha256 == null ||
            current.sha256 != expected.sha256 ||
            current.storedBytes != current.draft.declaration.size
    )
        documentFailure("client.document_file_changed", FailureKind.Conflict)
    else AppResult.Success(Unit)

/** Uncertain remote work must retain its original file, confirmation and idempotency keys. */
fun canRemoveDocumentFile(file: LocalDocumentFile): Boolean =
    isDocumentUploadRejected(file.upload) ||
        when (val step = file.upload?.step) {
            null -> true
            is DocumentUploadStep.Finished ->
                step.revision.status in
                    setOf(
                        DocumentRevisionStatus.READY,
                        DocumentRevisionStatus.REJECTED,
                        DocumentRevisionStatus.CANCELLED,
                        DocumentRevisionStatus.EXPIRED,
                        DocumentRevisionStatus.RETIRED,
                    )
            else -> false
        }
