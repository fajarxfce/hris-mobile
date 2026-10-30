package dev.fajar.starter.documents.domain.metadata.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.*
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.policies.*
import dev.fajar.starter.documents.domain.policies.documentFailure
import dev.fajar.starter.workspace.domain.entities.PendingActionSummary

/** Original commands suppress only matching input; later or incomplete edits remain drafts. */
fun documentMetadataPendingSummary(
    access: DocumentAccess,
    commands: List<DocumentMetadataCommandRecord>,
    page: DocumentMetadataWorkingPage,
): AppResult<PendingActionSummary> {
    if (commands.size > 128 || page.items.size > 20)
        return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
    val byDocument = commands.associateBy { it.command.change.documentId }
    val ids = page.items.map { it.head.id }
    if (
        byDocument.size != commands.size ||
            commands.any {
                !it.command.belongsTo(access) ||
                    validateDocumentMetadataCommand(it.command) is AppResult.Failed
            } ||
            ids != ids.distinct().sorted() ||
            page.items.any { validateDocumentMetadataWorkingCopy(it) is AppResult.Failed } ||
            (page.nextDocumentId != null &&
                (page.items.size != 20 || page.nextDocumentId != ids.lastOrNull()))
    )
        return documentFailure("client.document_scope_changed", FailureKind.AccessDenied)
    return AppResult.Success(
        PendingActionSummary(
            drafts =
                page.items.count { copy ->
                    hasMetadataWorkingEdits(copy) &&
                        byDocument[copy.head.id]?.let {
                            matchesAcknowledgedMetadataCommand(copy, it.command)
                        } != true
                },
            queued = commands.count { it.outcome is DocumentMetadataCommandOutcome.Pending },
            unconfirmed =
                commands.count { it.outcome is DocumentMetadataCommandOutcome.Unconfirmed },
            needsReview = commands.count { it.outcome is DocumentMetadataCommandOutcome.Rejected },
            moreDrafts = page.nextDocumentId != null,
        )
    )
}

private fun hasMetadataWorkingEdits(copy: DocumentMetadataWorkingCopy): Boolean {
    val metadata = copy.head.metadata ?: return false
    return copy.reason.isNotEmpty() ||
        copy.input !=
            DocumentMetadataInput(
                metadata.category,
                metadata.identityNumber.orEmpty(),
                metadata.issuingCountry.orEmpty(),
                metadata.issuedOn?.toString().orEmpty(),
                metadata.validUntil?.toString().orEmpty(),
            )
}
