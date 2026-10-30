package dev.fajar.starter.documents.data.metadata.working.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.encoding.documentMetadataScope
import dev.fajar.starter.documents.data.metadata.mappers.requireDocumentMetadataId
import dev.fajar.starter.documents.data.metadata.working.encoding.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.working.entities.*
import dev.fajar.starter.documents.domain.metadata.working.repositories.DocumentMetadataWorkingRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.*

@Single
class EncryptedDocumentMetadataWorkingRepository(
    private val records: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : DocumentMetadataWorkingRepository {
    private val codec = DocumentMetadataWorkingCodec(cipher)

    override fun observe(access: DocumentAccess, documentId: String) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observePublishedCheckpoint(
                            documentMetadataScope(access),
                            metadataWorkingPartition(access, documentId),
                        )
                    )
                },
                mapValue = { raw ->
                    if (raw == null) null else withKey { codec.open(access, documentId, raw, it) }
                },
            )
            .flowOn(Dispatchers.Default)

    override fun observePage(access: DocumentAccess, afterDocumentId: String?) =
        safeStorageFlow(
                flow {
                    val prefix = metadataWorkingPrefix(access)
                    afterDocumentId?.let(::requireDocumentMetadataId)
                    emitAll(
                        records.observePublishedCheckpoints(
                            documentMetadataScope(access),
                            prefix,
                            afterDocumentId?.let { prefix + it },
                            21,
                        )
                    )
                },
                mapValue = { rows ->
                    require(rows.size <= 21)
                    val ids =
                        rows.map { it.handle.partition.removePrefix(metadataWorkingPrefix(access)) }
                    ids.forEach(::requireDocumentMetadataId)
                    require(
                        ids == ids.distinct().sorted() &&
                            (afterDocumentId == null || ids.all { it > afterDocumentId })
                    )
                    val selected = rows.take(20)
                    val copies =
                        if (selected.isEmpty()) emptyList()
                        else
                            withKey { key ->
                                selected.mapIndexed { index, raw ->
                                    codec.open(access, ids[index], raw, key)
                                }
                            }
                    DocumentMetadataWorkingPage(
                        copies,
                        if (rows.size > 20) copies.last().head.id else null,
                    )
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun current(access: DocumentAccess, documentId: String) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val raw =
                records.checkpoint(
                    documentMetadataScope(access),
                    metadataWorkingPartition(access, documentId),
                )
            if (raw == null) null else withKey { codec.open(access, documentId, raw, it) }
        }
    }

    override suspend fun save(
        access: DocumentAccess,
        expected: DocumentMetadataWorkingCopy?,
        next: DocumentMetadataWorkingCopy,
    ) = safeStorageCall {
        withContext(Dispatchers.Default) {
            require(next.revision == (expected?.revision?.plus(1) ?: 0L))
            if (expected != null)
                require(next.generation == expected.generation && next.head == expected.head)
            val scope = documentMetadataScope(access)
            val partition = metadataWorkingPartition(access, next.head.id)
            val raw = records.checkpoint(scope, partition)
            if ((raw == null) != (expected == null)) return@withContext false
            withKey { key ->
                if (raw != null && codec.open(access, next.head.id, raw, key) != expected)
                    return@withKey false
                val payload = codec.seal(access, next, key)
                currentCoroutineContext().ensureActive()
                if (raw == null)
                    records.replace(
                        scope,
                        partition,
                        null,
                        ReplicaSnapshot(next.generation, payload, emptyList()),
                    )
                else records.commit(raw.handle, raw.revision, payload, emptyList(), publish = true)
            }
        }
    }

    override suspend fun remove(access: DocumentAccess, expected: DocumentMetadataWorkingCopy) =
        safeStorageCall {
            withContext(Dispatchers.Default) {
                val scope = documentMetadataScope(access)
                val partition = metadataWorkingPartition(access, expected.head.id)
                val raw = records.checkpoint(scope, partition) ?: return@withContext false
                withKey { key ->
                    if (codec.open(access, expected.head.id, raw, key) != expected)
                        return@withKey false
                    currentCoroutineContext().ensureActive()
                    records.replace(scope, partition, raw, null)
                }
            }
        }

    private suspend fun <T> withKey(block: suspend (ByteArray) -> T): T {
        val key =
            Base64.decode(
                requireNotNull(credentials.read()) { "Workspace content key is unavailable." }
            )
        return try {
            require(key.size == 32)
            block(key)
        } finally {
            key.fill(0)
        }
    }
}
