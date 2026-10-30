package dev.fajar.starter.documents.domain.metadata.working.policies

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.metadata.commands.entities.DocumentMetadataCommand
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.documents.domain.policies.prepareDocumentBusinessMetadata

/** Only unchanged input matching this receipt may be cleaned up; later edits keep their draft. */
fun matchesAcknowledgedMetadataCommand(
    copy: DocumentMetadataWorkingCopy,
    command: DocumentMetadataCommand,
): Boolean {
    if (
        copy.head.id != command.change.documentId ||
            copy.head.employmentId != command.employmentId ||
            copy.head.version != command.change.expectedVersion ||
            copy.updatedAt > command.createdAt ||
            copy.reason.trim() != command.change.reason
    )
        return false
    val value = prepareDocumentBusinessMetadata(copy.input)
    return value is AppResult.Success && value.value == command.change.metadata
}
