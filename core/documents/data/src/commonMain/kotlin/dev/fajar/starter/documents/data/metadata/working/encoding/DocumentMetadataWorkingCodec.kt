package dev.fajar.starter.documents.data.metadata.working.encoding

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.documents.data.metadata.encoding.documentMetadataScope
import dev.fajar.starter.documents.data.metadata.mappers.requireDocumentMetadataId
import dev.fajar.starter.documents.data.metadata.working.dto.DocumentMetadataWorkingDto
import dev.fajar.starter.documents.data.metadata.working.mappers.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

fun metadataWorkingPrefix(access: DocumentAccess): String {
    requireDocumentMetadataId(access.companyId)
    return "document-metadata-working/${access.companyId}/"
}

fun metadataWorkingPartition(access: DocumentAccess, id: String): String {
    requireDocumentMetadataId(id)
    return metadataWorkingPrefix(access) + id
}

private fun workingAssociatedData(
    access: DocumentAccess,
    id: String,
    generation: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-document-metadata-working-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                generation,
                revision.toString(),
            )
        )
        .encodeToByteArray()

internal class DocumentMetadataWorkingCodec(private val cipher: ContentCipher) {
    suspend fun seal(
        access: DocumentAccess,
        copy: DocumentMetadataWorkingCopy,
        key: ByteArray,
    ): String {
        val dto = copy.toWorkingDto()
        require(dto.toWorkingCopy(copy.generation, copy.revision, copy.head.id) == copy)
        val bytes = Json.encodeToString(dto).encodeToByteArray()
        return try {
            require(bytes.size in 1..16_384)
            val encrypted =
                cipher.encrypt(
                    key,
                    workingAssociatedData(access, copy.head.id, copy.generation, copy.revision),
                    bytes,
                )
            currentCoroutineContext().ensureActive()
            Base64.encode(encrypted)
        } finally {
            bytes.fill(0)
        }
    }

    suspend fun open(
        access: DocumentAccess,
        id: String,
        raw: ReplicaCheckpoint,
        key: ByteArray,
    ): DocumentMetadataWorkingCopy {
        require(
            raw.handle.scope == documentMetadataScope(access) &&
                raw.handle.partition == metadataWorkingPartition(access, id)
        )
        require(raw.published && raw.revision in 0..9_007_199_254_740_991L)
        requireDocumentMetadataId(raw.handle.generation)
        require(raw.payload.length in 1..24_000)
        val bytes =
            cipher.decrypt(
                key,
                workingAssociatedData(access, id, raw.handle.generation, raw.revision),
                Base64.decode(raw.payload),
            )
        return try {
            currentCoroutineContext().ensureActive()
            require(bytes.size in 1..16_384)
            Json.decodeFromString<DocumentMetadataWorkingDto>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
                .toWorkingCopy(raw.handle.generation, raw.revision, id)
        } finally {
            bytes.fill(0)
        }
    }
}
