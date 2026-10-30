package dev.fajar.starter.documents.data.metadata.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.dto.*
import dev.fajar.starter.documents.data.metadata.mappers.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

suspend fun encodeMetadataCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    checkpoint: DocumentMetadataCheckpoint,
): String =
    sealMetadata(
        cipher,
        key,
        documentMetadataAssociatedData(
            access,
            checkpoint.head.id,
            checkpoint.generation,
            "checkpoint",
            checkpoint.revision,
        ),
        Json.encodeToString(checkpoint.toMetadataCheckpointDto()),
    )

suspend fun decodeMetadataCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    id: String,
    raw: ReplicaCheckpoint,
): DocumentMetadataCheckpoint {
    require(raw.handle.scope == documentMetadataScope(access))
    require(raw.handle.partition == documentMetadataPartition(access, id))
    require(raw.published && raw.revision in 0..DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION)
    requireDocumentMetadataId(raw.handle.generation)
    val dto =
        Json.decodeFromString<DocumentMetadataCheckpointDto>(
            openMetadata(
                cipher,
                key,
                documentMetadataAssociatedData(
                    access,
                    id,
                    raw.handle.generation,
                    "checkpoint",
                    raw.revision,
                ),
                raw.payload,
            )
        )
    require(dto.format == 1 && dto.refreshedAt.length <= 40)
    val head = dto.head.toMetadataHead(id)
    require(
        if (head.metadataSupported)
            dto.historyThrough != null &&
                dto.historyThrough in 0..requireNotNull(head.metadataVersion)
        else dto.historyThrough == null
    )
    return DocumentMetadataCheckpoint(
        raw.handle.generation,
        raw.revision,
        head,
        Instant.parse(dto.refreshedAt),
        dto.historyThrough,
    )
}

suspend fun encodeMetadataRevision(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    generation: String,
    revision: DocumentMetadataRevision,
): ReplicaDocument {
    val dto = revision.toMetadataRevisionDto()
    require(dto.toMetadataRevision(revision.documentId) == revision)
    val id = documentMetadataHistoryKey(revision.version)
    val payload =
        sealMetadata(
            cipher,
            key,
            documentMetadataAssociatedData(
                access,
                revision.documentId,
                generation,
                id,
                revision.version,
            ),
            Json.encodeToString(dto),
        )
    return ReplicaDocument(id, revision.version, id, payload)
}

suspend fun decodeMetadataRevision(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    documentId: String,
    generation: String,
    raw: ReplicaDocument,
): DocumentMetadataRevision {
    val id = documentMetadataHistoryKey(raw.version)
    require(raw.id == id && raw.sortKey == id && raw.tags.isEmpty())
    return Json.decodeFromString<DocumentMetadataRevisionDto>(
            openMetadata(
                cipher,
                key,
                documentMetadataAssociatedData(access, documentId, generation, id, raw.version),
                requireNotNull(raw.payload),
            )
        )
        .toMetadataRevision(documentId)
        .also { require(it.version == raw.version) }
}

private suspend fun sealMetadata(
    cipher: ContentCipher,
    key: ByteArray,
    aad: ByteArray,
    text: String,
): String {
    val bytes = text.encodeToByteArray()
    return try {
        require(bytes.size in 1..16_384)
        Base64.encode(cipher.encrypt(key, aad, bytes)).also {
            currentCoroutineContext().ensureActive()
        }
    } finally {
        bytes.fill(0)
    }
}

private suspend fun openMetadata(
    cipher: ContentCipher,
    key: ByteArray,
    aad: ByteArray,
    payload: String,
): String {
    require(payload.length in 1..24_000)
    val bytes = cipher.decrypt(key, aad, Base64.decode(payload))
    return try {
        currentCoroutineContext().ensureActive()
        require(bytes.size in 1..16_384)
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
    }
}
