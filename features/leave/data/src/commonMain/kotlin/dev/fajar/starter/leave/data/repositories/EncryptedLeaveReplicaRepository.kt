package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.dto.LeaveDirectoryDto
import dev.fajar.starter.leave.data.encoding.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.repositories.LeaveReplicaRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.*
import dev.fajar.starter.sync.domain.entities.SyncCollection
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedLeaveReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("leave-replica-content-key") private val credentials: CredentialStore,
) : LeaveReplicaRepository {
    private val keyCreation = Mutex()

    override fun observeDirectory(access: LeaveAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                leaveReplicaScope(access),
                leaveReplicaPartition(access),
                "directory",
            ),
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) LeaveReplicaDirectory(null, null)
                else {
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Content key is unavailable." }
                        )
                    try {
                        val checkpoint = decodeLeaveCheckpoint(cipher, key, access, raw)
                        val document = page.documents.single()
                        require(document.id == "directory" && document.version == raw.revision)
                        val directory =
                            Json.decodeFromString<LeaveDirectoryDto>(
                                    decodeLeaveDocument(cipher, key, access, raw, document)
                                )
                                .toReplicaDirectory(access)
                        LeaveReplicaDirectory(checkpoint, directory)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override fun observe(access: LeaveAccess, selection: LeaveSelection) =
        safeStorageFlow(
            flow {
                val employeeId = requireNotNull(selection.employeeId)
                val year = requireNotNull(selection.year)
                val key =
                    Base64.decode(
                        requireNotNull(credentials.read()) { "Content key is unavailable." }
                    )
                val tags =
                    try {
                        leaveReplicaTags(
                            key,
                            access,
                            employeeId,
                            listOf(
                                listOf("balances", year.toString()),
                                listOf("requests", selection.status?.name ?: "ALL"),
                            ),
                        )
                    } finally {
                        key.fill(0)
                    }
                emitAll(
                    replicas.observeQueries(
                        leaveReplicaScope(access),
                        leaveReplicaPartition(access),
                        listOf(
                            ReplicaQuery(tags[0], selection.balanceAfter, 21),
                            ReplicaQuery(tags[1], selection.requestAfter, 21),
                        ),
                    )
                )
            },
            mapValue = { page ->
                if (page.checkpoint == null)
                    LeaveReplicaPage(null, LeavePage(emptyList()), LeavePage(emptyList()))
                else {
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Content key is unavailable." }
                        )
                    try {
                        decodeLeaveReplicaPage(cipher, key, access, selection, page)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun checkpoint(access: LeaveAccess) = safeStorageCall {
        val raw = replicas.checkpoint(leaveReplicaScope(access), leaveReplicaPartition(access))
        if (raw == null) null
        else {
            val key =
                Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
            try {
                decodeLeaveCheckpoint(cipher, key, access, raw)
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun begin(checkpoint: LeaveCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    val saved = credentials.read()
                    if (saved != null) Base64.decode(saved)
                    else {
                        check(!replicas.containsRecords("leave-replica/")) {
                            "Existing replica requires its content key."
                        }
                        val generated = cipher.generateKey()
                        try {
                            withContext(NonCancellable) {
                                credentials.write(Base64.encode(generated))
                            }
                            generated.copyOf()
                        } finally {
                            generated.fill(0)
                        }
                    }
                }
            try {
                val sealed = encodeLeaveCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    leaveReplicaScope(checkpoint.access),
                    leaveReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: LeaveCheckpoint,
        next: LeaveCheckpoint,
        directory: LeaveDirectory,
        entries: List<LeaveReplicaEntry>,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(next.generation == previous.generation && next.revision == previous.revision + 1)
        require(next.access == previous.access && (!previous.published || next.published))
        val access = previous.access
        require(directory.accountId == access.accountId && directory.companyId == access.companyId)
        require(directory.items.size <= 200 && directory.nextCursor == null)
        val batch = entries.toList()
        require(
            batch.size <= 50 &&
                batch.distinctBy { it.reference.collection to it.reference.id }.size == batch.size
        )
        val key =
            Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
        try {
            val documents =
                batch.map { entry ->
                    val reference = entry.reference
                    val id = leaveReplicaId(reference.collection, reference.id)
                    val record = entry.record
                    if (record == null) ReplicaDocument(id, reference.version, "deleted/$id", null)
                    else {
                        val (sortKey, json) =
                            when (record) {
                                is LeaveReplicaRecord.Request -> {
                                    require(reference.collection == SyncCollection.LEAVE_REQUESTS)
                                    require(
                                        reference.id == record.value.id &&
                                            reference.version == record.value.version
                                    )
                                    record.value.replicaSortKey() to
                                        Json.encodeToString(record.value.toDto())
                                }
                                is LeaveReplicaRecord.Balance -> {
                                    require(reference.collection == SyncCollection.LEAVE_BALANCES)
                                    require(
                                        reference.id == record.value.accountId &&
                                            reference.version == record.value.version
                                    )
                                    record.value.replicaSortKey() to
                                        Json.encodeToString(record.toDto())
                                }
                            }
                        encodeLeaveDocument(
                            cipher,
                            key,
                            next,
                            id,
                            reference.version,
                            sortKey,
                            leaveRecordTags(key, access, record),
                            json,
                        )
                    }
                } +
                    encodeLeaveDocument(
                        cipher,
                        key,
                        next,
                        "directory",
                        next.revision,
                        "directory",
                        emptySet(),
                        Json.encodeToString(directory.toDto()),
                    )
            val sealed = encodeLeaveCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    leaveReplicaScope(access),
                    leaveReplicaPartition(access),
                    previous.generation,
                ),
                previous.revision,
                sealed,
                documents,
                next.published,
            )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: LeaveAccess) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        // A confirmed scope denial must not fall back to an older last-page projection.
        if (!replicas.clear(ReplicaScope(access.sessionId), leavePartition(access))) false
        else {
            currentCoroutineContext().ensureActive()
            replicas.clear(leaveReplicaScope(access), leaveReplicaPartition(access))
        }
    }
}
