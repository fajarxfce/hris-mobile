@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.replica.repositories

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.sync.domain.entities.SyncCollection
import dev.fajar.starter.workforce.data.attendance.mappers.attendancePartition
import dev.fajar.starter.workforce.data.dto.WorkforceDirectoryDto
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.replica.datasources.WorkforceReadAdmissionDataSource
import dev.fajar.starter.workforce.data.replica.dto.WorkforceReadAdmissionDto
import dev.fajar.starter.workforce.data.replica.dto.WorkforceRecordDto
import dev.fajar.starter.workforce.data.replica.encoding.*
import dev.fajar.starter.workforce.data.replica.mappers.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceWindow
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.daysUntil
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedWorkforceReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workforce-replica-content-key") private val credentials: CredentialStore,
    private val admissions: WorkforceReadAdmissionDataSource,
) : WorkforceReplicaRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: WorkforceAccess) =
        admissions
            .observe(workforceAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            workforceReplicaScope(access),
                            workforceAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) WorkforceReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodeWorkforceReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: WorkforceAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: WorkforceAccess, failure: Failure) = safeStorageCall {
        val bufferKey = workforceAdmissionBufferKey(access)
        val dto =
            WorkforceReadAdmissionDto(Uuid.random().toString(), failure.kind.name, failure.code)
        dto.toReadAdmission()
        // Publish the confirmed denial even when the following durable write cannot complete.
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            val scope = workforceReplicaScope(access)
            val partition = workforceAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock {
                    readOrCreateWorkforceReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeWorkforceReadAdmission(cipher, key, access, dto)
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

    override suspend fun admit(access: WorkforceAccess, expectedRevision: String?) =
        safeStorageCall {
            admissionWrites.withLock {
                val bufferKey = workforceAdmissionBufferKey(access)
                val scope = workforceReplicaScope(access)
                val partition = workforceAdmissionPartition(access)
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

    override fun observeDirectory(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                workforceReplicaScope(access),
                workforceReplicaPartition(access),
                "directory",
            ),
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) WorkforceReplicaDirectory(null, null)
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodeWorkforceCheckpoint(cipher, key, access, raw)
                        if (checkpoint.refreshedAt == null) WorkforceReplicaDirectory(null, null)
                        else {
                            val document = page.documents.single()
                            require(document.id == "directory" && document.version == raw.revision)
                            val directory =
                                Json.decodeFromString<WorkforceDirectoryDto>(
                                        decodeWorkforceDocument(
                                            cipher,
                                            key,
                                            access,
                                            raw.handle.generation,
                                            document,
                                        )
                                    )
                                    .toWorkforceReplicaDirectory(access)
                            WorkforceReplicaDirectory(checkpoint, directory)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeFacts(access: WorkforceAccess, employeeId: String, period: CalendarPeriod) =
        safeStorageFlow(
            replicas
                .observeCheckpoint(workforceReplicaScope(access), workforceReplicaPartition(access))
                .map { it != null }
                .distinctUntilChanged()
                .flatMapLatest { retained ->
                    if (!retained) flowOf(ReplicaQueryPage(null, List(4) { emptyList() }))
                    else
                        flow {
                            val days = period.from.daysUntil(period.until) + 1
                            require(days in 1..31)
                            val key = Base64.decode(requireNotNull(credentials.read()))
                            val tags =
                                try {
                                    workforceReplicaTags(key, access, employeeId)
                                } finally {
                                    key.fill(0)
                                }
                            val queries =
                                workforceRecordCollections.mapIndexed { index, collection ->
                                    val start =
                                        if (collection == SyncCollection.WORK_SCHEDULES)
                                            period.until
                                        else period.from
                                    ReplicaQuery(
                                        tags[index],
                                        workforceDateKey(collection, start),
                                        days + 1,
                                    )
                                }
                            emitAll(
                                replicas.observeQueries(
                                    workforceReplicaScope(access),
                                    workforceReplicaPartition(access),
                                    queries,
                                )
                            )
                        }
                },
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null)
                    WorkforceReplicaFacts(null, emptyList(), emptyList(), emptyList(), emptyList())
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodeWorkforceCheckpoint(cipher, key, access, raw)
                        require(page.pages.size == workforceRecordCollections.size)
                        val records =
                            page.pages.mapIndexed { index, documents ->
                                require(
                                    documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey }
                                )
                                documents.map { document ->
                                    val record =
                                        Json.decodeFromString<WorkforceRecordDto>(
                                                decodeWorkforceDocument(
                                                    cipher,
                                                    key,
                                                    access,
                                                    raw.handle.generation,
                                                    document,
                                                )
                                            )
                                            .toRecord()
                                    require(
                                        record.collection() == workforceRecordCollections[index]
                                    )
                                    require(
                                        document.id ==
                                            workforceRecordId(record.collection(), record.id) &&
                                            document.version == record.version
                                    )
                                    require(document.sortKey == record.replicaSortKey())
                                    require(
                                        document.tags ==
                                            setOf(workforceRecordTag(key, access, record))
                                    )
                                    require(
                                        record.employeeId() == null ||
                                            record.employeeId() == employeeId
                                    )
                                    record
                                }
                            }
                        workforceReplicaFacts(checkpoint, period, records.flatten())
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override fun observePublishedCheckpoint(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observePublishedCheckpoint(
                workforceReplicaScope(access),
                workforceReplicaPartition(access),
            ),
            mapValue = { raw ->
                if (raw == null) null
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        decodeWorkforceCheckpoint(cipher, key, access, raw)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun checkpoint(access: WorkforceAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(workforceReplicaScope(access), workforceReplicaPartition(access))
                ?: return@safeStorageCall null
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            decodeWorkforceCheckpoint(cipher, key, access, raw)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun begin(checkpoint: WorkforceCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    readOrCreateWorkforceReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeWorkforceCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    workforceReplicaScope(checkpoint.access),
                    workforceReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: WorkforceCheckpoint,
        next: WorkforceCheckpoint,
        directory: WorkforceDirectory,
        entries: List<WorkforceReplicaEntry>,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(next.generation == previous.generation && next.revision == previous.revision + 1)
        require(next.access == previous.access && (!previous.published || next.published))
        val access = previous.access
        directory.toDto().toWorkforceReplicaDirectory(access)
        val batch = entries.toList()
        require(
            batch.size <= 32 &&
                batch.distinctBy { it.reference.collection to it.reference.id }.size == batch.size
        )
        val employees = directory.items.map { it.id }.toSet()
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val documents =
                batch.map { entry ->
                    val reference = entry.reference
                    val id = workforceRecordId(reference.collection, reference.id)
                    val record = entry.record
                    if (record == null) ReplicaDocument(id, reference.version, "deleted/$id", null)
                    else {
                        require(
                            record.id == reference.id &&
                                record.version == reference.version &&
                                record.collection() == reference.collection
                        )
                        require(record.employeeId() == null || record.employeeId() in employees)
                        val dto = record.toDto()
                        require(dto.toRecord() == record)
                        encodeWorkforceDocument(
                            cipher,
                            key,
                            next,
                            id,
                            record.version,
                            record.replicaSortKey(),
                            setOf(workforceRecordTag(key, access, record)),
                            Json.encodeToString(dto),
                        )
                    }
                } +
                    encodeWorkforceDocument(
                        cipher,
                        key,
                        next,
                        "directory",
                        next.revision,
                        "directory",
                        emptySet(),
                        Json.encodeToString(directory.toDto()),
                    )
            val sealed = encodeWorkforceCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    workforceReplicaScope(access),
                    workforceReplicaPartition(access),
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

    override suspend fun clear(access: WorkforceAccess) = safeStorageCall {
        // A denied replica must not fall back to its pre-upgrade last-view snapshot.
        val legacy =
            listOf(workCalendarPartition(access)) +
                AttendanceWindow.entries.map { attendancePartition(access, it) }
        for (partition in legacy) {
            if (!replicas.clear(ReplicaScope(access.sessionId), partition))
                return@safeStorageCall false
        }
        currentCoroutineContext().ensureActive()
        replicas.clear(workforceReplicaScope(access), workforceReplicaPartition(access))
    }
}
