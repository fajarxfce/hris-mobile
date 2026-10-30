package dev.fajar.starter.documents.data.metadata.commands.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.mappers.*
import dev.fajar.starter.documents.data.metadata.commands.dto.*
import dev.fajar.starter.documents.data.metadata.mappers.requireDocumentMetadataId
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.*
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataChange
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlin.time.Instant
import kotlinx.serialization.json.Json

fun metadataCommandScope(access: DocumentAccess) =
    CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun metadataCommandPartition(access: DocumentAccess): String {
    requireDocumentMetadataId(access.companyId)
    return "hris.document-metadata-commands/" + access.companyId
}

fun DocumentMetadataCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + change.operationId

fun metadataCommandAssociatedData(
    access: DocumentAccess,
    id: String,
    documentId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-document-metadata-command-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                documentId,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

fun DocumentMetadataCommand.toDto() =
    DocumentMetadataCommandDto(
        accountId,
        audience,
        companyId,
        employmentId,
        documentTitle,
        change.operationId,
        change.documentId,
        change.expectedVersion,
        change.metadata.toBusinessMetadataDto(),
        change.reason,
        createdAt.toString(),
    )

fun DocumentMetadataCommandDto.toCommand(): DocumentMetadataCommand {
    val command =
        DocumentMetadataCommand(
            accountId,
            audience,
            companyId,
            employmentId,
            documentTitle,
            DocumentMetadataChange(
                operationId,
                documentId,
                expectedVersion,
                metadata.toBusinessMetadata(),
                reason,
            ),
            Instant.parse(createdAt),
        )
    require(validateDocumentMetadataCommand(command) is AppResult.Success)
    return command
}

fun DocumentMetadataCommandOutcomeDto.toRecord(
    command: DocumentMetadataCommand,
    raw: CommandRecord,
    access: DocumentAccess,
): DocumentMetadataCommandRecord {
    require(command.belongsTo(access))
    require(
        raw.partition == metadataCommandPartition(access) &&
            raw.id == command.change.operationId &&
            raw.resourceId == command.change.documentId &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L)
    return DocumentMetadataCommandRecord(
        command,
        raw.revision,
        attempts,
        toOutcome(command.change.expectedVersion + 1),
    )
}
