@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSpaceDirectoryAdmissionSource
import dev.fajar.starter.collaboration.data.mappers.collaborationId
import dev.fajar.starter.collaboration.data.replica.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
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
class EncryptedCollaborationSpaceDirectoryRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-space-directory-content-key")
    private val credentials: AtomicCredentialStore,
    private val admissions: CollaborationSpaceDirectoryAdmissionSource,
) : CollaborationSpaceDirectoryRepository {
    private val keyCreation = Mutex()
    private val admissionWrites = Mutex()

    override suspend fun checkpoint(access: CollaborationSpaceLocalAccess) = safeStorageCall {
        replicas.checkpoint(directoryScope(access), directoryPartition(access))?.let { raw ->
            withKey(access) { it.checkpoint(raw) }
        }
    }

    override suspend fun begin(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceDirectoryCheckpoint?,
        generation: String,
        startedAt: Instant,
    ) = safeStorageCall {
        collaborationId(generation)
        require(startedAt.toEpochMilliseconds() >= 0)
        val scope = directoryScope(access)
        val partition = directoryPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        withKey(access, create = true) { codec ->
            val previous = raw?.let { codec.checkpoint(it) }
            if (previous != expected || previous?.complete == false) return@withKey false
            val next =
                CollaborationSpaceDirectoryCheckpoint(
                    generation,
                    0,
                    0,
                    startedAt,
                    null,
                    null,
                    complete = false,
                    published = false,
                    publishPartial = previous == null,
                )
            val sealed = codec.checkpoint(next)
            currentCoroutineContext().ensureActive()
            replicas.begin(scope, partition, generation, sealed, previous?.generation)
        }
    }

    override suspend fun append(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceDirectoryCheckpoint,
        page: CollaborationSpaceContactPage,
        acquiredAt: Instant,
    ) = safeStorageCall {
        require(acquiredAt.toEpochMilliseconds() >= 0)
        val captured =
            page.copy(items = page.items.map { it.copy(companies = it.companies.toList()) })
        require(captured.owner.localDirectoryAccess() == access)
        require(
            validCollaborationSpaceContactPage(
                captured,
                captured.owner,
                expected.nextCursor,
                COLLABORATION_SPACE_DIRECTORY_PAGE_SIZE,
            )
        )
        val raw =
            replicas.checkpoint(directoryScope(access), directoryPartition(access))
                ?: return@safeStorageCall false
        withKey(access) { codec ->
            val previous = codec.checkpoint(raw)
            if (previous != expected || previous.complete) return@withKey false
            val complete = captured.nextCursor == null
            val count = previous.count + captured.items.size
            require(
                count <= COLLABORATION_SPACE_DIRECTORY_CAPACITY &&
                    (complete || count < COLLABORATION_SPACE_DIRECTORY_CAPACITY)
            )
            val next =
                previous.copy(
                    revision = previous.revision + 1,
                    count = count,
                    acquiredAt = acquiredAt,
                    nextCursor = captured.nextCursor,
                    complete = complete,
                    published = complete || previous.publishPartial,
                )
            val documents = captured.items.map { codec.contact(next, it) }
            val sealed = codec.checkpoint(next)
            currentCoroutineContext().ensureActive()
            replicas.commit(raw.handle, raw.revision, sealed, documents, next.published)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>> =
        safeStorageFlow(
                flow {
                    emitAll(
                        replicas.observe(
                            directoryScope(access),
                            directoryPartition(access),
                            limit = 200,
                        )
                    )
                }
            )
            .mapLatest { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> safeStorageCall { snapshot(access, result.value) }
                }
            }
            .mapNotNull { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success -> result.value?.let { AppResult.Success(it) }
                }
            }
            .flowOn(Dispatchers.Default)

    private suspend fun snapshot(
        access: CollaborationSpaceLocalAccess,
        first: ReplicaPage,
    ): CollaborationSpaceDirectorySnapshot? {
        val raw = first.checkpoint
        if (raw == null) {
            require(first.documents.isEmpty())
            return CollaborationSpaceDirectorySnapshot(null, emptyList())
        }
        require(raw.published)
        return withKey(access) { codec ->
            val checkpoint = codec.checkpoint(raw)
            val contacts = mutableListOf<CollaborationSpaceContact>()
            var page = first
            var after: String? = null
            while (true) {
                currentCoroutineContext().ensureActive()
                if (page.checkpoint != raw) return@withKey null
                require(page.documents.size == minOf(200, checkpoint.count - contacts.size))
                for (document in page.documents) {
                    require(after == null || document.id > requireNotNull(after))
                    contacts += codec.contact(checkpoint, document)
                    after = document.id
                }
                if (contacts.size == checkpoint.count) break
                check(
                    contacts.size < COLLABORATION_SPACE_DIRECTORY_CAPACITY &&
                        page.documents.isNotEmpty()
                )
                yield()
                page =
                    replicas
                        .observe(directoryScope(access), directoryPartition(access), after, 200)
                        .first()
            }
            CollaborationSpaceDirectorySnapshot(checkpoint, contacts.toList())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceDirectoryAdmission>> =
        safeStorageFlow(flow { emitAll(admissions.observe(directoryAdmissionKey(access))) })
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success ->
                        result.value?.let { pending ->
                            safeStorageFlow(
                                flowOf(pending),
                                mapValue = { it.toDirectoryAdmission() },
                            )
                        }
                            ?: safeStorageFlow(
                                flow {
                                    emitAll(
                                        replicas.observeCheckpoint(
                                            directoryScope(access),
                                            directoryAdmissionPartition(access),
                                        )
                                    )
                                },
                                mapValue = { raw ->
                                    if (raw == null)
                                        CollaborationSpaceDirectoryAdmission(null, null)
                                    else
                                        withKey(access) { it.admission(raw).toDirectoryAdmission() }
                                },
                            )
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    override suspend fun admission(access: CollaborationSpaceLocalAccess) =
        observeAdmission(access).first()

    override suspend fun restrict(access: CollaborationSpaceLocalAccess, failure: Failure) =
        safeStorageCall {
            directoryScope(access)
            val key = directoryAdmissionKey(access)
            val dto =
                SpaceDirectoryAdmissionDto(
                    Uuid.random().toString(),
                    failure.kind.name,
                    failure.code,
                )
            dto.toDirectoryAdmission()
            admissions.write(key, dto)
            admissionWrites.withLock {
                val scope = directoryScope(access)
                val partition = directoryAdmissionPartition(access)
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
                    if (saved) admissions.clearIfCurrent(key, dto)
                    saved
                }
            }
        }

    override suspend fun admit(access: CollaborationSpaceLocalAccess, expectedRevision: String?) =
        safeStorageCall {
            admissionWrites.withLock {
                val key = directoryAdmissionKey(access)
                val scope = directoryScope(access)
                val partition = directoryAdmissionPartition(access)
                val previous = replicas.checkpoint(scope, partition)
                val pending = admissions.current(key)
                if ((pending?.revision ?: previous?.handle?.generation) != expectedRevision)
                    return@withLock false
                previous?.let { withKey(access) { codec -> codec.admission(it) } }
                currentCoroutineContext().ensureActive()
                if (!replicas.replace(scope, partition, previous, null)) return@withLock false
                admissions.clearIfCurrent(key, pending)
            }
        }

    private suspend fun <T> withKey(
        access: CollaborationSpaceLocalAccess,
        create: Boolean = false,
        action: suspend (CollaborationSpaceDirectoryCodec) -> T,
    ): T {
        val key =
            if (create) keyCreation.withLock { acquireKey() }
            else decodeKey(requireNotNull(credentials.read()))
        return try {
            action(CollaborationSpaceDirectoryCodec(cipher, key, access))
        } finally {
            key.fill(0)
        }
    }

    private fun decodeKey(raw: String): ByteArray {
        require(raw.length == 44)
        val key = Base64.decode(raw)
        if (key.size != 32 || Base64.encode(key) != raw) {
            key.fill(0)
            error("Invalid directory key")
        }
        return key
    }

    private suspend fun acquireKey(): ByteArray {
        credentials.read()?.let {
            return decodeKey(it)
        }
        check(
            !replicas.containsRecords(SPACE_DIRECTORY_PREFIX) &&
                !replicas.containsRecords(SPACE_DIRECTORY_ADMISSION_PREFIX)
        )
        val created = cipher.generateKey()
        return try {
            require(created.size == 32)
            decodeKey(credentials.writeIfAbsent(Base64.encode(created)))
        } finally {
            created.fill(0)
        }
    }
}
