package dev.fajar.starter.documents.data.metadata.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.encoding.*
import dev.fajar.starter.documents.data.metadata.mappers.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.*
import dev.fajar.starter.documents.domain.metadata.policies.*
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataCacheRepository
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedDocumentMetadataCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : DocumentMetadataCacheRepository {
    override fun observe(access: DocumentAccess, documentId: String, after: Long?) =
        safeStorageFlow(
                flow {
                    require(after == null || after in 0..DOCUMENT_METADATA_MAXIMUM_VERSION)
                    emitAll(
                        replicas.observe(
                            documentMetadataScope(access),
                            documentMetadataPartition(access, documentId),
                            after?.let(::documentMetadataHistoryKey),
                            DOCUMENT_METADATA_PAGE_SIZE,
                        )
                    )
                },
                mapValue = { page ->
                    if (page.checkpoint == null)
                        DocumentMetadataSnapshot(
                            null,
                            after,
                            DocumentMetadataPage(emptyList(), null),
                            false,
                        )
                    else {
                        val key = readKey()
                        try {
                            decodeMetadataSnapshot(cipher, key, access, documentId, after, page)
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .buffer(0)
            .flowOn(Dispatchers.Default)

    override suspend fun checkpoint(access: DocumentAccess, documentId: String) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val raw =
                replicas.checkpoint(
                    documentMetadataScope(access),
                    documentMetadataPartition(access, documentId),
                ) ?: return@withContext null
            val key = readKey()
            try {
                decodeMetadataCheckpoint(cipher, key, access, documentId, raw)
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun commit(
        access: DocumentAccess,
        expected: DocumentMetadataCheckpoint?,
        next: DocumentMetadataCheckpoint,
        rows: List<DocumentMetadataRevision>,
    ) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val scope = documentMetadataScope(access)
            val partition = documentMetadataPartition(access, next.head.id)
            val previous = replicas.checkpoint(scope, partition)
            if (
                previous?.handle?.generation != expected?.generation ||
                    previous?.revision != expected?.revision
            )
                return@withContext false
            requireMetadataCacheWrite(expected, next, rows)
            val key = readKey()
            try {
                if (
                    previous != null &&
                        decodeMetadataCheckpoint(cipher, key, access, next.head.id, previous) !=
                            expected
                )
                    return@withContext false
                requireHistoryNeighbors(access, next, rows, key)
                val encoded = mutableListOf<ReplicaDocument>()
                for (row in rows) {
                    val raw =
                        replicas.document(scope, partition, documentMetadataHistoryKey(row.version))
                    if (raw == null)
                        encoded += encodeMetadataRevision(cipher, key, access, next.generation, row)
                    else
                        require(
                            decodeMetadataRevision(
                                cipher,
                                key,
                                access,
                                next.head.id,
                                next.generation,
                                raw,
                            ) == row
                        )
                }
                val checkpoint = encodeMetadataCheckpoint(cipher, key, access, next)
                currentCoroutineContext().ensureActive()
                if (previous == null)
                    replicas.replace(
                        scope,
                        partition,
                        null,
                        ReplicaSnapshot(next.generation, checkpoint, encoded),
                    )
                else replicas.commit(previous.handle, previous.revision, checkpoint, encoded, true)
            } finally {
                key.fill(0)
            }
        }
    }

    /** The owned workspace provisions this existing key; a metadata cache never replaces it. */
    private suspend fun readKey() =
        Base64.decode(requireNotNull(credentials.read())).also { require(it.size == 32) }

    private suspend fun requireHistoryNeighbors(
        access: DocumentAccess,
        next: DocumentMetadataCheckpoint,
        rows: List<DocumentMetadataRevision>,
        key: ByteArray,
    ) {
        if (rows.isEmpty()) return
        val neighbors =
            listOf(rows.first().version - 1, rows.last().version + 1).filter { it in 0..999 }
        for (version in neighbors) {
            val raw =
                replicas.document(
                    documentMetadataScope(access),
                    documentMetadataPartition(access, next.head.id),
                    documentMetadataHistoryKey(version),
                ) ?: continue
            val stored =
                decodeMetadataRevision(cipher, key, access, next.head.id, next.generation, raw)
            if (version < rows.first().version)
                require(stored.documentVersion < rows.first().documentVersion)
            else require(stored.documentVersion > rows.last().documentVersion)
        }
    }
}

private fun requireMetadataCacheWrite(
    expected: DocumentMetadataCheckpoint?,
    next: DocumentMetadataCheckpoint,
    rows: List<DocumentMetadataRevision>,
) {
    requireDocumentMetadataId(next.generation)
    require(next.head.toMetadataHeadDto().toMetadataHead(next.head.id) == next.head)
    require(
        next.revision == (expected?.revision ?: -1L) + 1 &&
            next.revision in 0..DOCUMENT_METADATA_MAXIMUM_DOCUMENT_VERSION
    )
    require(
        expected == null ||
            (next.generation == expected.generation &&
                compatibleDocumentMetadataHeads(expected.head, next.head))
    )
    require(expected?.head?.metadataSupported != true || next.head.metadataSupported)
    require(
        rows.size <= DOCUMENT_METADATA_PAGE_SIZE && rows.distinctBy { it.version }.size == rows.size
    )
    require(
        rows.zipWithNext().all { (a, b) ->
            b.version == a.version + 1 && b.documentVersion > a.documentVersion
        }
    )
    val before = expected?.historyThrough ?: -1L
    val through = next.historyThrough ?: -1L
    require(through >= before)
    require(
        if (next.head.metadataSupported) through in 0..requireNotNull(next.head.metadataVersion)
        else through == -1L
    )
    require(through - before <= DOCUMENT_METADATA_PAGE_SIZE)
    require((before + 1..through).all { version -> rows.any { it.version == version } })
    for (row in rows) {
        require(
            row.documentId == next.head.id &&
                row.version in 0..through &&
                row.documentVersion <= next.head.version
        )
        if (row.version == next.head.metadataVersion) require(row.metadata == next.head.metadata)
    }
}
