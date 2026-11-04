@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationListAdmissionDataSource
import dev.fajar.starter.collaboration.data.mappers.collaborationId
import dev.fajar.starter.collaboration.data.replica.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationListRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-list-content-key") private val credentials: AtomicCredentialStore,
    private val admissions: CollaborationListAdmissionDataSource,
) : CollaborationListRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    override suspend fun get(access: CollaborationLocalAccess, conversationId: String) =
        safeStorageCall {
            val page =
                replicas
                    .observeDocument(
                        listScope(access),
                        listPartition(access),
                        collaborationId(conversationId),
                    )
                    .first()
            require(page.documents.size <= 1)
            val raw = page.checkpoint
            if (raw == null) {
                require(page.documents.isEmpty())
                null
            } else
                withKey(access) { codec ->
                    val checkpoint = codec.checkpoint(raw)
                    page.documents.singleOrNull()?.let {
                        require(it.id == conversationId)
                        codec.document(checkpoint, it)
                    }
                }
        }

    override fun observe(
        access: CollaborationLocalAccess,
        selection: CollaborationListSelection,
    ): Flow<AppResult<CollaborationLocalList>> =
        safeStorageFlow(
                flow {
                    require(selection.limit in 1..50)
                    selection.after?.let(::collaborationId)
                    emitAll(
                        replicas.observe(
                            listScope(access),
                            listPartition(access),
                            selection.after,
                            selection.limit + 1,
                        )
                    )
                },
                mapValue = { page ->
                    val raw = page.checkpoint
                    if (raw == null) {
                        require(page.documents.isEmpty())
                        CollaborationLocalList(null, emptyList(), null)
                    } else
                        withKey(access) { codec ->
                            val checkpoint = codec.checkpoint(raw)
                            require(
                                page.documents.size <= selection.limit + 1 &&
                                    page.documents.size <= checkpoint.count
                            )
                            val ids = page.documents.map { it.id }
                            require(
                                ids.zipWithNext().all { (a, b) -> a < b } &&
                                    (selection.after == null ||
                                        ids.all { it > requireNotNull(selection.after) })
                            )
                            val items = page.documents.map { codec.document(checkpoint, it) }
                            CollaborationLocalList(
                                checkpoint,
                                items.take(selection.limit),
                                if (items.size > selection.limit) ids[selection.limit - 1] else null,
                            )
                        }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun checkpoint(access: CollaborationLocalAccess) = safeStorageCall {
        val raw = replicas.checkpoint(listScope(access), listPartition(access))
        raw?.let { withKey(access) { codec -> codec.checkpoint(it) } }
    }

    override suspend fun merge(
        access: CollaborationLocalAccess,
        expected: CollaborationListCheckpoint?,
        items: List<CollaborationDirectSummary>,
        acquiredAt: Instant,
    ) = safeStorageCall {
        val batch = items.toList()
        require(
            batch.size <= 50 &&
                batch.map { it.conversation.id }.distinct().size == batch.size &&
                acquiredAt.toEpochMilliseconds() >= 0
        )
        val scope = listScope(access)
        val partition = listPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        withKey(access, create = raw == null) { codec ->
            val previous = raw?.let { codec.checkpoint(it) }
            if (previous != expected) return@withKey false
            var added = 0
            for (item in batch) {
                val old = replicas.document(scope, partition, collaborationId(item.conversation.id))
                if (old == null) added++
                else {
                    requireNotNull(previous)
                    require(codec.document(previous, old).conversation == item.conversation)
                }
            }
            val count = (previous?.count ?: 0) + added
            require(count <= COLLABORATION_LIST_CAPACITY)
            val next =
                CollaborationListCheckpoint(
                    previous?.generation ?: Uuid.random().toString(),
                    previous?.revision?.plus(1) ?: 0,
                    count,
                    acquiredAt,
                )
            val documents = batch.map { codec.document(next, it) }
            val sealed = codec.checkpoint(next)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(next.generation, sealed, documents),
                )
            else replicas.commit(raw.handle, raw.revision, sealed, documents, true)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(
        access: CollaborationLocalAccess
    ): Flow<AppResult<CollaborationListAdmission>> =
        safeStorageFlow(admissions.observe(listAdmissionKey(access)))
            .flatMapLatest { pending ->
                when (pending) {
                    is AppResult.Failed -> flowOf(pending)
                    is AppResult.Success ->
                        pending.value?.let {
                            safeStorageFlow(flowOf(it), mapValue = { value -> value.toAdmission() })
                        }
                            ?: safeStorageFlow(
                                replicas.observeCheckpoint(
                                    listScope(access),
                                    listAdmissionPartition(access),
                                ),
                                mapValue = { raw ->
                                    if (raw == null) CollaborationListAdmission(null, null)
                                    else withKey(access) { it.admission(raw).toAdmission() }
                                },
                            )
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    override suspend fun admission(access: CollaborationLocalAccess) =
        observeAdmission(access).first()

    override suspend fun restrict(access: CollaborationLocalAccess, failure: Failure) =
        safeStorageCall {
            val bufferKey = listAdmissionKey(access)
            val dto =
                CollaborationListAdmissionDto(
                    Uuid.random().toString(),
                    failure.kind.name,
                    failure.code,
                )
            dto.toAdmission()
            admissions.write(bufferKey, dto)
            admissionWrites.withLock {
                val scope = listScope(access)
                val partition = listAdmissionPartition(access)
                val previous = replicas.checkpoint(scope, partition)
                withKey(access, create = true) { codec ->
                    val sealed = codec.admission(dto)
                    currentCoroutineContext().ensureActive()
                    val saved =
                        replicas.replace(
                            scope,
                            partition,
                            previous,
                            ReplicaSnapshot(dto.revision, sealed, emptyList()),
                        )
                    if (saved) admissions.clearIfCurrent(bufferKey, dto)
                    saved
                }
            }
        }

    override suspend fun admit(access: CollaborationLocalAccess, expectedRevision: String?) =
        safeStorageCall {
            admissionWrites.withLock {
                val bufferKey = listAdmissionKey(access)
                val scope = listScope(access)
                val partition = listAdmissionPartition(access)
                val previous = replicas.checkpoint(scope, partition)
                val pending = admissions.current(bufferKey)
                if ((pending?.revision ?: previous?.handle?.generation) != expectedRevision)
                    return@withLock false
                // Authenticate a durable gate before removing it.
                previous?.let { withKey(access) { codec -> codec.admission(it) } }
                currentCoroutineContext().ensureActive()
                if (!replicas.replace(scope, partition, previous, null)) return@withLock false
                admissions.clearIfCurrent(bufferKey, pending)
            }
        }

    private suspend fun <T> withKey(
        access: CollaborationLocalAccess,
        create: Boolean = false,
        operation: suspend (CollaborationListCodec) -> T,
    ): T {
        val key =
            if (create) keyCreation.withLock { acquireKey() }
            else Base64.decode(requireNotNull(credentials.read()))
        return try {
            require(key.size == 32)
            operation(CollaborationListCodec(cipher, key, access))
        } finally {
            key.fill(0)
        }
    }

    private suspend fun acquireKey(): ByteArray {
        credentials.read()?.let {
            return Base64.decode(it)
        }
        check(
            !replicas.containsRecords(COLLABORATION_LIST_PREFIX) &&
                !replicas.containsRecords(COLLABORATION_LIST_ACCESS_PREFIX)
        )
        val generated = cipher.generateKey()
        return try {
            Base64.decode(credentials.writeIfAbsent(Base64.encode(generated)))
        } finally {
            generated.fill(0)
        }
    }
}
