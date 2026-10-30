@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.domain.metadata.commands.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.DocumentMetadataCommand
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.DOCUMENT_METADATA_MAXIMUM_VERSION
import dev.fajar.starter.documents.domain.policies.*
import kotlin.time.Instant

fun admitDocumentMetadataEditing(access: DocumentAccess): AppResult<Unit> =
    if ("documents.self.upload" in access.permissions) AppResult.Success(Unit)
    else documentFailure("document_access_denied", FailureKind.AccessDenied)

fun prepareDocumentMetadataCommand(
    access: DocumentAccess,
    observed: DocumentMetadataView,
    input: DocumentMetadataInput,
    reason: String,
    now: Instant,
    operationId: String,
): AppResult<DocumentMetadataCommand> {
    val allowed = admitDocumentMetadataEditing(access)
    if (allowed is AppResult.Failed) return allowed
    if (!sameDocumentScope(observed.access, access))
        return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
    val head =
        observed.snapshot.checkpoint?.head
            ?: return documentFailure("client.document_metadata_changed", FailureKind.Conflict)
    if (!head.metadataSupported || head.metadata == null || head.metadataVersion == null)
        return documentFailure("client.document_metadata_unsupported", FailureKind.Unavailable)
    if (head.metadataVersion >= DOCUMENT_METADATA_MAXIMUM_VERSION)
        return documentFailure("document_metadata_limit", FailureKind.Conflict)
    val value =
        when (val result = prepareDocumentBusinessMetadata(input)) {
            is AppResult.Failed -> return result
            is AppResult.Success -> result.value
        }
    if (value == head.metadata)
        return documentFailure("document_metadata_unchanged", FailureKind.Conflict)
    val command =
        DocumentMetadataCommand(
            access.accountId,
            access.audience,
            access.companyId,
            head.employmentId,
            head.title,
            DocumentMetadataChange(operationId, head.id, head.version, value, reason.trim()),
            now,
        )
    return when (val result = validateDocumentMetadataCommand(command)) {
        is AppResult.Failed -> result
        is AppResult.Success -> AppResult.Success(command)
    }
}
