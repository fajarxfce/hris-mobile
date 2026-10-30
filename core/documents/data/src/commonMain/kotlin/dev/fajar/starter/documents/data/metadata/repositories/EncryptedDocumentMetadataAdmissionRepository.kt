@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.metadata.repositories

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.datasources.DocumentMetadataAdmissionBuffer
import dev.fajar.starter.documents.data.metadata.dto.DocumentMetadataAdmissionDto
import dev.fajar.starter.documents.data.metadata.encoding.*
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.entities.DocumentMetadataReadAdmission
import dev.fajar.starter.documents.domain.metadata.repositories.DocumentMetadataAdmissionRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedDocumentMetadataAdmissionRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
    private val buffer: DocumentMetadataAdmissionBuffer,
) : DocumentMetadataAdmissionRepository {
    private val writes = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(access: DocumentAccess, documentId: String) =
        safeStorageFlow(
                flow {
                    val partition = metadataAdmissionPartition(access, documentId)
                    emitAll(
                        buffer
                            .observe(metadataAdmissionBufferKey(access, documentId))
                            .flatMapLatest { pending ->
                                if (pending != null) flowOf(pending to null)
                                else
                                    replicas
                                        .observeCheckpoint(documentMetadataScope(access), partition)
                                        .map { null to it }
                            }
                    )
                },
                mapValue = { (pending, raw) ->
                    if (pending != null) pending.toMetadataAdmission()
                    else if (raw == null) DocumentMetadataReadAdmission(null, null)
                    else {
                        val key = readKey()
                        try {
                            openMetadataAdmission(cipher, key, access, documentId, raw)
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .buffer(0)
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    override suspend fun current(access: DocumentAccess, documentId: String) =
        observe(access, documentId).first()

    override suspend fun restrict(access: DocumentAccess, documentId: String, failure: Failure) =
        safeStorageCall {
            val partition = metadataAdmissionPartition(access, documentId)
            val bufferKey = metadataAdmissionBufferKey(access, documentId)
            val dto =
                DocumentMetadataAdmissionDto(
                    Uuid.random().toString(),
                    failure.kind.name,
                    failure.code,
                )
            dto.toMetadataAdmission()
            val preceding = buffer.current(bufferKey)
            buffer.write(bufferKey, dto)
            writes.withLock {
                val scope = documentMetadataScope(access)
                val previous = replicas.checkpoint(scope, partition)
                val key = readKey()
                try {
                    val sealed = sealMetadataAdmission(cipher, key, access, documentId, dto)
                    currentCoroutineContext().ensureActive()
                    val saved =
                        replicas.replace(
                            scope,
                            partition,
                            previous,
                            ReplicaSnapshot(dto.revision, sealed, emptyList()),
                        )
                    if (buffer.current(bufferKey) == dto)
                        buffer.write(bufferKey, if (saved) null else preceding)
                    saved
                } finally {
                    key.fill(0)
                }
            }
        }

    override suspend fun admit(
        access: DocumentAccess,
        documentId: String,
        expectedRevision: String?,
    ) = safeStorageCall {
        writes.withLock {
            val bufferKey = metadataAdmissionBufferKey(access, documentId)
            val scope = documentMetadataScope(access)
            val partition = metadataAdmissionPartition(access, documentId)
            val previous = replicas.checkpoint(scope, partition)
            val pending = buffer.current(bufferKey)
            if ((pending?.revision ?: previous?.handle?.generation) != expectedRevision)
                return@withLock false
            currentCoroutineContext().ensureActive()
            if (!replicas.replace(scope, partition, previous, null)) return@withLock false
            if (buffer.current(bufferKey) != pending) return@withLock false
            buffer.write(bufferKey, null)
            true
        }
    }

    private suspend fun readKey() =
        Base64.decode(requireNotNull(credentials.read())).also { require(it.size == 32) }
}
