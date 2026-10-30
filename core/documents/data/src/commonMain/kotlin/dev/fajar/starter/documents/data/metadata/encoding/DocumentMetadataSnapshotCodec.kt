package dev.fajar.starter.documents.data.metadata.encoding

import dev.fajar.starter.database.ReplicaPage
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.DOCUMENT_METADATA_PAGE_SIZE
import dev.fajar.starter.securestorage.ContentCipher

suspend fun decodeMetadataSnapshot(
    cipher: ContentCipher,
    key: ByteArray,
    access: DocumentAccess,
    id: String,
    after: Long?,
    page: ReplicaPage,
): DocumentMetadataSnapshot {
    require(after == null || after in 0..999)
    val checkpoint =
        decodeMetadataCheckpoint(cipher, key, access, id, requireNotNull(page.checkpoint))
    val preceding = after ?: -1L
    val retainedCount =
        ((checkpoint.historyThrough ?: -1L) - preceding)
            .coerceIn(0, DOCUMENT_METADATA_PAGE_SIZE.toLong())
            .toInt()
    require(page.documents.size == retainedCount)
    val rows =
        page.documents.mapIndexed { index, raw ->
            decodeMetadataRevision(cipher, key, access, id, checkpoint.generation, raw).also {
                require(
                    it.version == preceding + index + 1 &&
                        it.documentVersion <= checkpoint.head.version
                )
                if (it.version == checkpoint.head.metadataVersion)
                    require(it.metadata == checkpoint.head.metadata)
            }
        }
    require(rows.zipWithNext().all { (a, b) -> b.documentVersion > a.documentVersion })
    val latest = checkpoint.head.metadataVersion ?: -1L
    val next = rows.lastOrNull()?.version?.takeIf { it < latest }
    val complete =
        retainedCount ==
            (latest - preceding).coerceIn(0, DOCUMENT_METADATA_PAGE_SIZE.toLong()).toInt()
    return DocumentMetadataSnapshot(checkpoint, after, DocumentMetadataPage(rows, next), complete)
}
