package dev.fajar.starter.documents.data.metadata.commands.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.data.metadata.commands.dto.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.DocumentMetadataCommandOutcome
import dev.fajar.starter.documents.domain.metadata.commands.policies.DOCUMENT_METADATA_MAXIMUM_ATTEMPTS

fun DocumentMetadataCommandOutcome.toDto(attempts: Int): DocumentMetadataCommandOutcomeDto {
    require(attempts in 0..DOCUMENT_METADATA_MAXIMUM_ATTEMPTS)
    return when (this) {
        DocumentMetadataCommandOutcome.Pending ->
            DocumentMetadataCommandOutcomeDto("PENDING", attempts)
        is DocumentMetadataCommandOutcome.Unconfirmed ->
            DocumentMetadataCommandOutcomeDto(
                "UNCONFIRMED",
                attempts,
                failure = failure?.toCommandDto(),
            )
        is DocumentMetadataCommandOutcome.Rejected ->
            DocumentMetadataCommandOutcomeDto(
                "REJECTED",
                attempts,
                failure = failure.toCommandDto(),
            )
        is DocumentMetadataCommandOutcome.Confirmed ->
            DocumentMetadataCommandOutcomeDto("CONFIRMED", attempts, version)
    }
}

private fun Failure.toCommandDto() =
    DocumentMetadataCommandFailureDto(kind.name, code, correlationId, retryAfterSeconds)

fun DocumentMetadataCommandOutcomeDto.toOutcome(
    expectedReceiptVersion: Long
): DocumentMetadataCommandOutcome {
    require(attempts in 0..DOCUMENT_METADATA_MAXIMUM_ATTEMPTS)
    require(receiptVersion == null || receiptVersion == expectedReceiptVersion)
    val reason =
        failure?.let {
            require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
            require(
                it.correlationId == null ||
                    it.correlationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))
            )
            require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
            Failure(
                FailureKind.valueOf(it.kind),
                "This document metadata change could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    return when (phase) {
        "PENDING" -> {
            require(attempts == 0 && receiptVersion == null && reason == null)
            DocumentMetadataCommandOutcome.Pending
        }
        "UNCONFIRMED" -> {
            require(attempts > 0 && receiptVersion == null)
            DocumentMetadataCommandOutcome.Unconfirmed(reason)
        }
        "REJECTED" -> {
            require(attempts > 0 && receiptVersion == null)
            DocumentMetadataCommandOutcome.Rejected(requireNotNull(reason))
        }
        "CONFIRMED" -> {
            require(attempts > 0 && reason == null)
            DocumentMetadataCommandOutcome.Confirmed(requireNotNull(receiptVersion))
        }
        else -> error("Unsupported document metadata command outcome.")
    }
}
