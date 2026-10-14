@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.profile.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.people.data.dto.*
import dev.fajar.starter.people.data.mappers.*
import dev.fajar.starter.people.data.profile.datasources.SelfProfileReadAdmissionDataSource
import dev.fajar.starter.people.data.profile.dto.SelfProfileReadAdmissionDto
import dev.fajar.starter.people.data.profile.dto.SelfProfileRecordDto
import dev.fajar.starter.people.data.profile.encoding.*
import dev.fajar.starter.people.data.profile.mappers.*
import dev.fajar.starter.people.domain.entities.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.repositories.SelfProfileReplicaRepository
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
class EncryptedSelfProfileReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("self-profile-replica-content-key") private val credentials: CredentialStore,
    private val admissions: SelfProfileReadAdmissionDataSource,
) : SelfProfileReplicaRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: SelfProfileAccess) =
        admissions
            .observe(selfProfileAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            selfProfileReplicaScope(access),
                            selfProfileAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) SelfProfileReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodeSelfProfileReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: SelfProfileAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: SelfProfileAccess, failure: Failure) = safeStorageCall {
        val bufferKey = selfProfileAdmissionBufferKey(access)
        val dto =
            SelfProfileReadAdmissionDto(Uuid.random().toString(), failure.kind.name, failure.code)
        dto.toReadAdmission()
        // Publish the confirmed denial even when the following durable write cannot complete.
        val preceding = admissions.current(bufferKey)
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            val scope = selfProfileReplicaScope(access)
            val partition = selfProfileAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock {
                    readOrCreateSelfProfileReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeSelfProfileReadAdmission(cipher, key, access, dto)
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

    override suspend fun admit(access: SelfProfileAccess, expectedRevision: String?) =
        safeStorageCall {
            admissionWrites.withLock {
                val bufferKey = selfProfileAdmissionBufferKey(access)
                val scope = selfProfileReplicaScope(access)
                val partition = selfProfileAdmissionPartition(access)
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
    override fun observe(access: SelfProfileAccess) =
        safeStorageFlow(
                replicas.observe(
                    selfProfileReplicaScope(access),
                    selfProfileReplicaPartition(access),
                    limit = 200,
                )
            )
            .mapLatest { initial ->
                when (initial) {
                    is AppResult.Failed -> initial
                    is AppResult.Success ->
                        safeStorageCall {
                            val first = initial.value
                            val raw = first.checkpoint
                            if (raw == null) SelfProfileSnapshot(null, emptyList())
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    val checkpoint =
                                        decodeSelfProfileCheckpoint(cipher, key, access, raw)
                                    val records = mutableListOf<SelfProfileRecord>()
                                    var page = first
                                    var after: String? = null
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        // A changing published generation is retried by the
                                        // upstream
                                        // storage emission.
                                        // Never assemble records from different committed
                                        // revisions.
                                        if (page.checkpoint != raw) return@safeStorageCall null
                                        require(page.documents.size <= 200)
                                        for (document in page.documents) {
                                            require(
                                                after == null ||
                                                    document.sortKey > requireNotNull(after)
                                            )
                                            require(
                                                document.tags.isEmpty() &&
                                                    document.sortKey == document.id
                                            )
                                            val record =
                                                Json.decodeFromString<SelfProfileRecordDto>(
                                                        decodeSelfProfileDocument(
                                                            cipher,
                                                            key,
                                                            access,
                                                            raw.handle.generation,
                                                            document,
                                                        )
                                                    )
                                                    .toRecord(
                                                        document.id.removePrefix("profile/"),
                                                        access.companyId,
                                                        access.accountId,
                                                    )
                                            require(
                                                document.id == "profile/${record.id}" &&
                                                    document.version == record.version
                                            )
                                            records += record
                                            require(records.size <= 200)
                                            after = document.sortKey
                                        }
                                        if (page.documents.size < 200) break
                                        yield()
                                        page =
                                            replicas
                                                .observe(
                                                    selfProfileReplicaScope(access),
                                                    selfProfileReplicaPartition(access),
                                                    after,
                                                    200,
                                                )
                                                .first()
                                    }
                                    require(
                                        records
                                            .distinctBy {
                                                it.profile.details.employee.employeeNumber
                                            }
                                            .size == records.size
                                    )
                                    // Release the encryption key before the collector retains this
                                    // immutable snapshot.
                                    SelfProfileSnapshot(checkpoint, records.toList())
                                } finally {
                                    key.fill(0)
                                }
                            }
                        }
                }
            }
            .mapNotNull { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> result.value?.let { AppResult.Success(it) }
                }
            }
            .buffer(0)
            .flowOn(Dispatchers.Default)

    override suspend fun checkpoint(access: SelfProfileAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(
                selfProfileReplicaScope(access),
                selfProfileReplicaPartition(access),
            ) ?: return@safeStorageCall null
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            decodeSelfProfileCheckpoint(cipher, key, access, raw)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun begin(checkpoint: SelfProfileCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    readOrCreateSelfProfileReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeSelfProfileCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    selfProfileReplicaScope(checkpoint.access),
                    selfProfileReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: SelfProfileCheckpoint,
        next: SelfProfileCheckpoint,
        entries: List<SelfProfileReplicaEntry>,
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
                require(reference.collection == SyncCollection.SELF_PROFILES)
                val id = "profile/${reference.id}"
                val record = entry.record
                if (record == null) {
                    require(Uuid.parse(reference.id).toString() == reference.id)
                    documents += ReplicaDocument(id, reference.version, id, null)
                } else {
                    val dto = record.toReplicaDto()
                    dto.toRecord(reference.id, access.companyId, access.accountId)
                    require(record.version == reference.version)
                    documents +=
                        encodeSelfProfileDocument(
                            cipher,
                            key,
                            next,
                            id,
                            reference.version,
                            id,
                            emptySet(),
                            Json.encodeToString(dto),
                        )
                }
            }
            val sealed = encodeSelfProfileCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    selfProfileReplicaScope(access),
                    selfProfileReplicaPartition(access),
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

    override suspend fun clear(access: SelfProfileAccess) = safeStorageCall {
        replicas.clear(selfProfileReplicaScope(access), selfProfileReplicaPartition(access))
    }
}
