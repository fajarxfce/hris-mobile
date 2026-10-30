package dev.fajar.starter.documents.data.metadata.commands.encoding

import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.documents.data.metadata.commands.dto.*
import dev.fajar.starter.documents.data.metadata.commands.mappers.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

internal class DocumentMetadataCommandCodec(private val cipher: ContentCipher) {
    suspend fun sealRequest(
        access: DocumentAccess,
        command: DocumentMetadataCommand,
        key: ByteArray,
    ): String {
        val bytes = Json.encodeToString(command.toDto()).encodeToByteArray()
        return try {
            require(bytes.size <= 16_384)
            Base64.encode(
                cipher.encrypt(
                    key,
                    metadataCommandAssociatedData(
                        access,
                        command.change.operationId,
                        command.change.documentId,
                        "request",
                        0,
                    ),
                    bytes,
                )
            )
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun sealOutcome(
        access: DocumentAccess,
        command: DocumentMetadataCommand,
        revision: Long,
        outcome: DocumentMetadataCommandOutcome,
        attempts: Int,
        key: ByteArray,
    ): String {
        val dto = outcome.toDto(attempts)
        dto.toOutcome(command.change.expectedVersion + 1)
        val bytes = Json.encodeToString(dto).encodeToByteArray()
        return try {
            require(bytes.size <= 4_096)
            Base64.encode(
                cipher.encrypt(
                    key,
                    metadataCommandAssociatedData(
                        access,
                        command.change.operationId,
                        command.change.documentId,
                        "outcome",
                        revision,
                    ),
                    bytes,
                )
            )
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun open(
        access: DocumentAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): DocumentMetadataCommandRecord {
        require(raw.request.length <= 24_576 && raw.state.length <= 8_192)
        val request =
            cipher.decrypt(
                key,
                metadataCommandAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                require(request.size <= 16_384)
                Json.decodeFromString<DocumentMetadataCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCommand()
            } finally {
                request.fill(0)
            }
        val state =
            cipher.decrypt(
                key,
                metadataCommandAssociatedData(
                    access,
                    raw.id,
                    raw.resourceId,
                    "outcome",
                    raw.revision,
                ),
                Base64.decode(raw.state),
            )
        return try {
            require(state.size <= 4_096)
            Json.decodeFromString<DocumentMetadataCommandOutcomeDto>(
                    state.decodeToString(throwOnInvalidSequence = true)
                )
                .toRecord(command, raw, access)
        } finally {
            state.fill(0)
        }
    }
}
