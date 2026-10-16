@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data.repositories

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.database.*
import dev.fajar.starter.inbox.data.datasources.InboxReadAdmissionDataSource
import dev.fajar.starter.inbox.data.dto.InboxReadAdmissionDto
import dev.fajar.starter.inbox.data.encoding.*
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.repositories.InboxCacheRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedInboxCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("inbox-content-key") private val credentials: CredentialStore,
    private val admissions: InboxReadAdmissionDataSource,
) : InboxCacheRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: InboxAccess) =
        admissions
            .observe(inboxAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            inboxReplicaScope(access),
                            inboxAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) InboxReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodeInboxReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: InboxAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: InboxAccess, failure: Failure) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        val bufferKey = inboxAdmissionBufferKey(access)
        val dto = InboxReadAdmissionDto(Uuid.random().toString(), failure.kind.name, failure.code)
        dto.toReadAdmission()
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            if (admissions.current(bufferKey) != dto) return@withLock false
            val scope = inboxReplicaScope(access)
            val partition = inboxAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock { readOrCreateInboxContentKey(replicas, cipher, credentials) }
            try {
                val sealed = encodeInboxReadAdmission(cipher, key, access, dto)
                currentCoroutineContext().ensureActive()
                val saved =
                    replicas.replace(
                        scope,
                        partition,
                        previous,
                        ReplicaSnapshot(dto.revision, sealed, emptyList()),
                    )
                if (saved && admissions.current(bufferKey) == dto) admissions.write(bufferKey, null)
                saved
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun admit(access: InboxAccess, expectedRevision: String?) = safeStorageCall {
        admissionWrites.withLock {
            val bufferKey = inboxAdmissionBufferKey(access)
            val scope = inboxReplicaScope(access)
            val partition = inboxAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val pending = admissions.current(bufferKey)
            if ((pending?.revision ?: previous?.handle?.generation) != expectedRevision)
                return@withLock false
            currentCoroutineContext().ensureActive()
            if (!replicas.replace(scope, partition, previous, null)) return@withLock false
            if (admissions.current(bufferKey) != pending) return@withLock false
            admissions.write(bufferKey, null)
            true
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(access: InboxAccess, query: InboxCacheQuery) =
        safeStorageFlow(
            when (query) {
                InboxCacheQuery.Checkpoint ->
                    replicas
                        .observeCheckpoint(inboxReplicaScope(access), inboxPartition(access))
                        .map { ReplicaPage(it, emptyList()) }
                is InboxCacheQuery.Page ->
                    replicas.observe(
                        inboxReplicaScope(access),
                        inboxPartition(access),
                        query.after,
                        31,
                    )
                is InboxCacheQuery.Item ->
                    replicas.observeDocument(
                        inboxReplicaScope(access),
                        inboxPartition(access),
                        query.id,
                    )
            }.flatMapLatest { owned ->
                if (owned.checkpoint != null) flowOf(owned)
                else
                    when (query) {
                        InboxCacheQuery.Checkpoint ->
                            replicas
                                .observeCheckpoint(
                                    ReplicaScope(access.sessionId),
                                    inboxPartition(access),
                                )
                                .map { ReplicaPage(it, emptyList()) }
                        is InboxCacheQuery.Page ->
                            replicas.observe(
                                ReplicaScope(access.sessionId),
                                inboxPartition(access),
                                query.after,
                                31,
                            )
                        is InboxCacheQuery.Item ->
                            replicas.observeDocument(
                                ReplicaScope(access.sessionId),
                                inboxPartition(access),
                                query.id,
                            )
                    }
            },
            mapValue = { page ->
                if (page.checkpoint == null) InboxCachePage(null, emptyList(), null)
                else {
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Content key is unavailable." }
                        )
                    try {
                        decodeInboxPage(cipher, key, access, page)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun checkpoint(access: InboxAccess) = safeStorageCall {
        val raw = replicas.checkpoint(inboxReplicaScope(access), inboxPartition(access))
        if (raw == null) null
        else {
            val key =
                Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
            try {
                decodeInboxCheckpoint(cipher, key, access, raw)
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun begin(checkpoint: InboxCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            require(checkpoint.revision == 0L && !checkpoint.published)
            val access = checkpoint.access
            val key =
                keyCreation.withLock { readOrCreateInboxContentKey(replicas, cipher, credentials) }
            try {
                val sealed =
                    cipher.encrypt(
                        key,
                        inboxAssociatedData(access, checkpoint.generation, "checkpoint", "", 0),
                        Json.encodeToString(checkpoint.toDto()).encodeToByteArray(),
                    )
                replicas.begin(
                    inboxReplicaScope(access),
                    inboxPartition(access),
                    checkpoint.generation,
                    Base64.encode(sealed),
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: InboxCheckpoint,
        next: InboxCheckpoint,
        entries: List<InboxReplicaEntry>,
    ) = safeStorageCall {
        require(
            next.generation == previous.generation &&
                next.revision == previous.revision + 1 &&
                next.access == previous.access
        )
        require(!previous.published || next.published)
        val access = previous.access
        val batch = entries.toList()
        require(batch.size <= 50 && batch.map { it.id }.distinct().size == batch.size)
        val key =
            Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
        try {
            val documents =
                batch.map { entry ->
                    val item = entry.item
                    if (item == null) ReplicaDocument(entry.id, entry.version, entry.id, null)
                    else {
                        require(entry.id == item.id && entry.version == item.version)
                        val bytes =
                            cipher.encrypt(
                                key,
                                inboxAssociatedData(
                                    access,
                                    previous.generation,
                                    "item",
                                    entry.id,
                                    entry.version,
                                ),
                                Json.encodeToString(item.toDto()).encodeToByteArray(),
                            )
                        ReplicaDocument(
                            entry.id,
                            entry.version,
                            item.sortKey(),
                            Base64.encode(bytes),
                        )
                    }
                }
            val sealed =
                cipher.encrypt(
                    key,
                    inboxAssociatedData(access, next.generation, "checkpoint", "", next.revision),
                    Json.encodeToString(next.toDto()).encodeToByteArray(),
                )
            replicas.commit(
                ReplicaHandle(
                    inboxReplicaScope(access),
                    inboxPartition(access),
                    previous.generation,
                ),
                previous.revision,
                Base64.encode(sealed),
                documents,
                next.published,
            )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: InboxAccess) = safeStorageCall {
        if (!replicas.clear(ReplicaScope(access.sessionId), inboxPartition(access))) false
        else replicas.clear(inboxReplicaScope(access), inboxPartition(access))
    }
}
