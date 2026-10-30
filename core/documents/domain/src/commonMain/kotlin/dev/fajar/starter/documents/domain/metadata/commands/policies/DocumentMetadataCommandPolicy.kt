@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.commands.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.validateDocumentMetadataChange
import dev.fajar.starter.documents.domain.policies.documentFailure
import kotlin.uuid.Uuid

const val DOCUMENT_METADATA_MAXIMUM_ATTEMPTS = 1_000

fun DocumentMetadataCommand.belongsTo(access: DocumentAccess) =
    accountId == access.accountId && audience == access.audience && companyId == access.companyId

fun validateDocumentMetadataCommand(command: DocumentMetadataCommand): AppResult<Unit> {
    if (
        listOf(command.accountId, command.companyId, command.employmentId).any {
            Uuid.parseOrNull(it)?.toString() != it
        } ||
            command.audience.length !in 1..2048 ||
            command.audience.any(Char::isISOControl) ||
            command.documentTitle.isBlank() ||
            command.documentTitle.length > 160 ||
            command.createdAt.toEpochMilliseconds() !in 0 until 253_402_300_800_000L
    )
        return documentFailure("client.document_metadata_change_invalid")
    return when (val result = validateDocumentMetadataChange(command.change)) {
        is AppResult.Failed -> result
        is AppResult.Success -> AppResult.Success(Unit)
    }
}

/** A rejection following any prior uncertain attempt cannot disprove its earlier commit. */
fun resolveDocumentMetadataCommandFailure(
    previous: DocumentMetadataCommandOutcome,
    failure: Failure,
): DocumentMetadataCommandOutcome =
    when (previous) {
        is DocumentMetadataCommandOutcome.Confirmed,
        is DocumentMetadataCommandOutcome.Rejected -> previous
        is DocumentMetadataCommandOutcome.Unconfirmed ->
            DocumentMetadataCommandOutcome.Unconfirmed(failure)
        DocumentMetadataCommandOutcome.Pending ->
            if (failure.code in definiteRejections) DocumentMetadataCommandOutcome.Rejected(failure)
            else DocumentMetadataCommandOutcome.Unconfirmed(failure)
    }

private val definiteRejections =
    setOf(
        "invalid_document_metadata",
        "invalid_document_metadata_change",
        "document_metadata_limit",
        "document_metadata_unchanged",
        "document_archived",
        "document_not_found",
        "document_access_denied",
        "stale_version",
        "company_access_denied",
        "company_not_found",
        "session_revoked",
        "access_denied",
        "forbidden",
        "mfa_required",
        "mfa_setup_required",
        "recent_authentication_required",
        "mfa_verification_required",
    )
