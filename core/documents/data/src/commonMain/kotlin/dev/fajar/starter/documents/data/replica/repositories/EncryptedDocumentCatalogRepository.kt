@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.dto.*
import dev.fajar.starter.documents.data.mappers.*
import dev.fajar.starter.documents.data.replica.datasources.DocumentCatalogReadAdmissionDataSource
import dev.fajar.starter.documents.data.replica.dto.DocumentCatalogReadAdmissionDto
import dev.fajar.starter.documents.data.replica.encoding.*
import dev.fajar.starter.documents.data.replica.mappers.*
import dev.fajar.starter.documents.domain.entities.*
import dev.fajar.starter.documents.domain.policies.DOCUMENT_PAGE_SIZE
import dev.fajar.starter.documents.domain.replica.entities.*
import dev.fajar.starter.documents.domain.replica.policies.validateDocumentCatalogSelection
import dev.fajar.starter.documents.domain.replica.repositories.DocumentCatalogRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedDocumentCatalogRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("document-catalog-content-key") private val credentials: CredentialStore,
    private val admissions: DocumentCatalogReadAdmissionDataSource,
) : DocumentCatalogRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: DocumentAccess) =
        admissions
            .observe(documentCatalogAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            documentCatalogReplicaScope(access),
                            documentCatalogAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) DocumentCatalogReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodeDocumentCatalogReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: DocumentAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: DocumentAccess, failure: Failure) = safeStorageCall {
        val bufferKey = documentCatalogAdmissionBufferKey(access)
        val dto =
            DocumentCatalogReadAdmissionDto(
                Uuid.random().toString(),
                failure.kind.name,
                failure.code,
            )
        dto.toReadAdmission()
        // Publish the confirmed denial even when the following durable write cannot complete.
        val preceding = admissions.current(bufferKey)
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            val scope = documentCatalogReplicaScope(access)
            val partition = documentCatalogAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock {
                    readOrCreateDocumentCatalogReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeDocumentCatalogReadAdmission(cipher, key, access, dto)
                currentCoroutineContext().ensureActive()
                val saved =
                    replicas.replace(
                        scope,
                        partition,
                        previous,
                        ReplicaSnapshot(dto.revision, sealed, emptyList()),
                    )
                if (admissions.current(bufferKey) == dto) {
                    // A rejected stale-session write must not fence a newer login.
                    admissions.write(bufferKey, if (saved) null else preceding)
                }
                saved
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun admit(access: DocumentAccess, expectedRevision: String?) =
        safeStorageCall {
            admissionWrites.withLock {
                val bufferKey = documentCatalogAdmissionBufferKey(access)
                val scope = documentCatalogReplicaScope(access)
                val partition = documentCatalogAdmissionPartition(access)
                val previous = replicas.checkpoint(scope, partition)
                val pending = admissions.current(bufferKey)
                if ((pending?.revision ?: previous?.handle?.generation) != expectedRevision)
                    return@withLock false
                currentCoroutineContext().ensureActive()
                if (!replicas.replace(scope, partition, previous, null)) return@withLock false
                // A denial arriving while storage was pending belongs to a later acquisition.
                if (admissions.current(bufferKey) != pending) return@withLock false
                admissions.write(bufferKey, null)
                true
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(access: DocumentAccess, selection: DocumentCatalogSelection) =
        safeStorageFlow(
                replicas
                    .observeCheckpoint(
                        documentCatalogReplicaScope(access),
                        documentCatalogReplicaPartition(access),
                    )
                    .map { it != null }
                    .distinctUntilChanged()
                    .flatMapLatest { retained ->
                        if (!retained) flowOf(ReplicaQueryPage(null, listOf(emptyList())))
                        else
                            flow {
                                require(
                                    validateDocumentCatalogSelection(selection) is AppResult.Success
                                )
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                val tag =
                                    try {
                                        documentCatalogIndex(
                                            key,
                                            access,
                                            selection.employmentId.orEmpty(),
                                            selection.classification,
                                        )
                                    } finally {
                                        key.fill(0)
                                    }
                                emitAll(
                                    replicas.observeQueries(
                                        documentCatalogReplicaScope(access),
                                        documentCatalogReplicaPartition(access),
                                        listOf(
                                            ReplicaQuery(
                                                tag,
                                                selection.after?.let { "document/$it" },
                                                DOCUMENT_PAGE_SIZE + 1,
                                            )
                                        ),
                                    )
                                )
                            }
                    },
                mapValue = { page ->
                    require(validateDocumentCatalogSelection(selection) is AppResult.Success)
                    val raw = page.checkpoint
                    if (raw == null)
                        DocumentCatalogSnapshot(null, selection, DocumentEvidencePage(emptyList()))
                    else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            decodeDocumentCatalogPage(cipher, key, access, selection, page)
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .buffer(0)
            .flowOn(Dispatchers.Default)

    override suspend fun checkpoint(access: DocumentAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(
                documentCatalogReplicaScope(access),
                documentCatalogReplicaPartition(access),
            ) ?: return@safeStorageCall null
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            decodeDocumentCatalogCheckpoint(cipher, key, access, raw)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun begin(checkpoint: DocumentCatalogCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    readOrCreateDocumentCatalogReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeDocumentCatalogCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    documentCatalogReplicaScope(checkpoint.access),
                    documentCatalogReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: DocumentCatalogCheckpoint,
        next: DocumentCatalogCheckpoint,
        entries: List<DocumentCatalogEntry>,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(next.generation == previous.generation && next.revision == previous.revision + 1)
        require(next.access == previous.access && (!previous.published || next.published))
        val access = previous.access
        val batch = entries.toList()
        require(
            batch.size <= 50 &&
                batch.distinctBy { it.reference.collection to it.reference.id }.size == batch.size
        )
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val documents = mutableListOf<ReplicaDocument>()
            for (entry in batch) {
                val reference = entry.reference
                require(reference.collection == SyncCollection.DOCUMENT_EVIDENCE)
                val id = "document/${reference.id}"
                val record = entry.record
                if (record == null) {
                    require(Uuid.parse(reference.id).toString() == reference.id)
                    documents += ReplicaDocument(id, reference.version, id, null)
                } else {
                    val dto = record.toCatalogDto()
                    dto.toCatalogRecord(reference.id)
                    require(record.version == reference.version)
                    documents +=
                        encodeDocumentCatalogDocument(
                            cipher,
                            key,
                            next,
                            id,
                            reference.version,
                            id,
                            setOf(
                                documentCatalogIndex(
                                    key,
                                    access,
                                    record.employmentId,
                                    record.classification,
                                )
                            ),
                            Json.encodeToString(dto),
                        )
                }
            }
            val sealed = encodeDocumentCatalogCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    documentCatalogReplicaScope(access),
                    documentCatalogReplicaPartition(access),
                    previous.generation,
                ),
                previous.revision,
                sealed,
                documents.toList(),
                next.published,
            )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: DocumentAccess) = safeStorageCall {
        replicas.clear(documentCatalogReplicaScope(access), documentCatalogReplicaPartition(access))
    }
}
