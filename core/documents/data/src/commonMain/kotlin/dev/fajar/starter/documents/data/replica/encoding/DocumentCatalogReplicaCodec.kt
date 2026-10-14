package dev.fajar.starter.documents.data.replica.encoding

import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.dto.DocumentCatalogRecordDto
import dev.fajar.starter.documents.data.mappers.toCatalogRecord
import dev.fajar.starter.documents.data.replica.dto.*
import dev.fajar.starter.documents.data.replica.mappers.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.entities.DocumentEvidencePage
import dev.fajar.starter.documents.domain.policies.DOCUMENT_PAGE_SIZE
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

suspend fun decodeDocumentCatalogCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    raw: ReplicaCheckpoint,
): DocumentCatalogCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            documentCatalogReplicaAssociatedData(
                access,
                raw.handle.generation,
                "checkpoint",
                raw.revision,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<DocumentCatalogCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeDocumentCatalogCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: DocumentCatalogCheckpoint,
): String {
    val bytes = Json.encodeToString(checkpoint.toReplicaDto()).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                documentCatalogReplicaAssociatedData(
                    checkpoint.access,
                    checkpoint.generation,
                    "checkpoint",
                    checkpoint.revision,
                ),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun encodeDocumentCatalogDocument(
    cipher: ContentCipher,
    key: ByteArray,
    checkpoint: DocumentCatalogCheckpoint,
    id: String,
    version: Long,
    sortKey: String,
    tags: Set<String>,
    json: String,
): ReplicaDocument {
    val bytes = json.encodeToByteArray()
    return try {
        require(bytes.size <= 96 * 1024 - 64)
        val encrypted =
            cipher.encrypt(
                key,
                documentCatalogReplicaAssociatedData(
                    checkpoint.access,
                    checkpoint.generation,
                    id,
                    version,
                    sortKey,
                    tags,
                ),
                bytes,
            )
        ReplicaDocument(id, version, sortKey, Base64.encode(encrypted), tags)
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeDocumentCatalogDocument(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    generation: String,
    document: ReplicaDocument,
): String {
    val bytes =
        cipher.decrypt(
            key,
            documentCatalogReplicaAssociatedData(
                access,
                generation,
                document.id,
                document.version,
                document.sortKey,
                document.tags,
            ),
            Base64.decode(requireNotNull(document.payload)),
        )
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
    }
}

/** Decodes one indexed page and verifies its persisted selection and ownership. */
suspend fun decodeDocumentCatalogPage(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    selection: DocumentCatalogSelection,
    page: ReplicaQueryPage,
): DocumentCatalogSnapshot {
    val raw = requireNotNull(page.checkpoint)
    val checkpoint = decodeDocumentCatalogCheckpoint(cipher, key, access, raw)
    val tag =
        documentCatalogIndex(
            key,
            access,
            selection.employmentId.orEmpty(),
            selection.classification,
        )
    val documents = page.pages.single()
    require(
        documents.size <= DOCUMENT_PAGE_SIZE + 1 &&
            documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey }
    )
    if (selection.employmentId == null) require(documents.isEmpty())
    val items =
        documents.map { document ->
            val record =
                Json.decodeFromString<DocumentCatalogRecordDto>(
                        decodeDocumentCatalogDocument(
                            cipher,
                            key,
                            access,
                            raw.handle.generation,
                            document,
                        )
                    )
                    .toCatalogRecord(document.id.removePrefix("document/"))
            require(document.id == "document/${record.id}" && document.version == record.version)
            require(document.sortKey == document.id && document.tags == setOf(tag))
            require(
                record.employmentId == selection.employmentId &&
                    record.classification == selection.classification
            )
            require(checkpoint.employments.any { it.id == record.employmentId })
            selection.after?.let { require(record.id > it) }
            record.evidence
        }
    val visible = items.take(DOCUMENT_PAGE_SIZE)
    return DocumentCatalogSnapshot(
        checkpoint,
        selection,
        DocumentEvidencePage(
            visible.toList(),
            if (items.size > DOCUMENT_PAGE_SIZE) visible.last().documentId else null,
        ),
    )
}
