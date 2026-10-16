@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.leave.data.datasources.LeaveReadAdmissionDataSource
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.data.encoding.*
import dev.fajar.starter.leave.data.mappers.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.initialLeaveCheckpoint
import dev.fajar.starter.leave.domain.policies.sameLeaveScope
import dev.fajar.starter.leave.domain.policies.sameStoredLeaveScope
import dev.fajar.starter.leave.domain.repositories.LeaveReplicaRepository
import dev.fajar.starter.leave.domain.repositories.LeaveRequestReplicaRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single(binds = [LeaveReplicaRepository::class, LeaveRequestReplicaRepository::class])
class EncryptedLeaveReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("leave-replica-content-key") private val credentials: CredentialStore,
    @Named("leave-content-key") private val legacyCredentials: CredentialStore,
    private val admissions: LeaveReadAdmissionDataSource,
    @Named("leave-content-key-creation") private val keyCreation: Mutex = Mutex(),
) : LeaveReplicaRepository {
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: LeaveAccess) =
        admissions
            .observe(leaveAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            leaveReplicaScope(access),
                            leaveAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) LeaveReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodeLeaveReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: LeaveAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: LeaveAccess, failure: Failure) = safeStorageCall {
        val bufferKey = leaveAdmissionBufferKey(access)
        val dto = LeaveReadAdmissionDto(Uuid.random().toString(), failure.kind.name, failure.code)
        dto.toReadAdmission()
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            if (admissions.current(bufferKey) != dto) return@withLock false
            val scope = leaveReplicaScope(access)
            val partition = leaveAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock { readOrCreateLeaveReplicaKey(replicas, cipher, credentials) }
            try {
                val sealed = encodeLeaveReadAdmission(cipher, key, access, dto)
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

    override suspend fun admit(access: LeaveAccess, expectedRevision: String?) = safeStorageCall {
        admissionWrites.withLock {
            val bufferKey = leaveAdmissionBufferKey(access)
            val scope = leaveReplicaScope(access)
            val partition = leaveAdmissionPartition(access)
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
                        if (checkpoint.refreshedAt == null) LeaveReplicaDirectory(null, null)
                        else {
                            val document = page.documents.single()
                            require(document.id == "directory" && document.version == raw.revision)
                            val directory =
                                Json.decodeFromString<LeaveDirectoryDto>(
                                        decodeLeaveDocument(
                                            cipher,
                                            key,
                                            access,
                                            raw.handle.generation,
                                            document,
                                        )
                                    )
                                    .toReplicaDirectory(access)
                            LeaveReplicaDirectory(checkpoint, directory)
                        }
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
                keyCreation.withLock { readOrCreateLeaveReplicaKey(replicas, cipher, credentials) }
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
        require(
            previous.recordSchema == LeaveCheckpoint.CURRENT_RECORD_SCHEMA &&
                next.recordSchema == LeaveCheckpoint.CURRENT_RECORD_SCHEMA
        )
        val access = previous.access
        require(directory.accountId == access.accountId && directory.companyId == access.companyId)
        require(directory.items.size <= 200 && directory.nextCursor == null)
        val batch = entries.toList()
        require(
            batch.size <= 8 &&
                batch.distinctBy { it.reference.collection to it.reference.id }.size == batch.size
        )
        val key =
            Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
        try {
            val documents = mutableListOf<ReplicaDocument>()
            for (entry in batch) {
                documents += encodeLeaveReplicaEntry(entry, next, cipher, key)
            }
            documents +=
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRequest(
        access: LeaveAccess,
        selection: LeaveRequestSelection,
    ): Flow<AppResult<LeaveRequestSnapshot?>> =
        safeStorageFlow(
                replicas.observeDocument(
                    leaveReplicaScope(access),
                    leaveReplicaPartition(access),
                    "request/${selection.requestId}",
                ),
                mapValue = { page ->
                    val raw = page.checkpoint
                    if (raw == null) null to null
                    else {
                        val key =
                            Base64.decode(
                                requireNotNull(credentials.read()) { "Content key is unavailable." }
                            )
                        try {
                            decodeLeaveCheckpoint(cipher, key, access, raw) to
                                page.documents.singleOrNull()?.version
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success -> {
                        val (checkpoint, version) = result.value
                        when {
                            checkpoint == null || checkpoint.recordSchema < 2 ->
                                observeLegacyRequest(access, selection.requestId)
                            version == null -> flowOf(AppResult.Success(null))
                            else ->
                                observePublishedRequest(
                                    access,
                                    selection,
                                    checkpoint.generation,
                                    version,
                                )
                        }
                    }
                }
            }

    /** The previous format contains one complete view, including its original history selection. */
    private fun observeLegacyRequest(
        access: LeaveAccess,
        requestId: String,
    ): Flow<AppResult<LeaveRequestSnapshot?>> =
        safeStorageFlow(
            replicas.observe(
                ReplicaScope(access.sessionId),
                leaveRequestPartition(access),
                limit = 32,
            ),
            mapValue = { legacy ->
                val old = legacy.checkpoint
                if (old == null) null
                else {
                    val key =
                        Base64.decode(
                            requireNotNull(legacyCredentials.read()) {
                                "Legacy content key is unavailable."
                            }
                        )
                    try {
                        decodeLeaveRequestCache(legacy, cipher, key, access)
                            .toSnapshot(access, old.handle.generation)
                            .takeIf { it.request.id == requestId }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    private fun observePublishedRequest(
        access: LeaveAccess,
        selection: LeaveRequestSelection,
        generation: String,
        version: Long,
    ): Flow<AppResult<LeaveRequestSnapshot?>> =
        safeStorageFlow(
            flow {
                val key =
                    Base64.decode(
                        requireNotNull(credentials.read()) { "Content key is unavailable." }
                    )
                val tags =
                    try {
                        listOf(
                                "request",
                                "detail/$version",
                                leaveActivityComponent(selection.historyAfter),
                            )
                            .map { leaveRequestComponentTag(key, access, selection.requestId, it) }
                    } finally {
                        key.fill(0)
                    }
                emitAll(
                    replicas
                        .observeQueries(
                            leaveReplicaScope(access),
                            leaveReplicaPartition(access),
                            listOf(
                                ReplicaQuery(tags[0], limit = 1),
                                ReplicaQuery(tags[1], limit = 4),
                                ReplicaQuery(tags[2], limit = 1),
                            ),
                        )
                        .filter {
                            it.checkpoint?.handle?.generation == generation &&
                                it.pages.firstOrNull()?.singleOrNull()?.version == version
                        }
                )
            },
            mapValue = { page ->
                val key =
                    Base64.decode(
                        requireNotNull(credentials.read()) { "Content key is unavailable." }
                    )
                try {
                    decodeLeaveRequestRecords(access, selection, page, cipher, key)
                } finally {
                    key.fill(0)
                }
            },
        )

    override suspend fun requestCheckpoint(access: LeaveAccess) = safeStorageCall {
        leaveWriteCheckpoint(
            replicas.checkpoint(leaveReplicaScope(access), leaveReplicaPartition(access))
        )
    }

    override suspend fun replaceRequest(
        access: LeaveAccess,
        requestId: String,
        expectedCheckpoint: String?,
        snapshot: LeaveRequestSnapshot?,
        history: LeaveHistoryPage?,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        val scope = leaveReplicaScope(access)
        val partition = leaveReplicaPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (leaveWriteCheckpoint(raw) != expectedCheckpoint) return@safeStorageCall false
        if (snapshot == null) {
            if (!replicas.clear(ReplicaScope(access.sessionId), leaveRequestPartition(access)))
                return@safeStorageCall false
            if (!replicas.clear(ReplicaScope(access.sessionId), leavePartition(access)))
                return@safeStorageCall false
            currentCoroutineContext().ensureActive()
            return@safeStorageCall replicas.clear(scope, partition)
        }
        if (raw != null && !raw.published) {
            // A replacement must keep its previously published generation intact.
            val visible = replicas.observeDocument(scope, partition, "directory").first()
            if (visible.checkpoint != null) return@safeStorageCall false
        }
        require(
            snapshot.request.id == requestId &&
                snapshot.selection == LeaveRequestSelection(requestId)
        )
        require(sameLeaveScope(access, snapshot.access))
        val key =
            keyCreation.withLock { readOrCreateLeaveReplicaKey(replicas, cipher, credentials) }
        try {
            val previous =
                if (raw == null)
                    initialLeaveCheckpoint(access, snapshot.generation, snapshot.fetchedAt)
                else decodeLeaveCheckpoint(cipher, key, access, raw)
            if (previous.recordSchema < 2 || !sameStoredLeaveScope(previous.access, access))
                return@safeStorageCall false
            val parent = replicas.document(scope, partition, "request/$requestId")
            if (parent != null && parent.version > snapshot.request.version)
                return@safeStorageCall false
            val directory = replicas.document(scope, partition, "directory")
            if (previous.refreshedAt != null && directory?.version != previous.revision)
                return@safeStorageCall false
            val next =
                previous.copy(
                    revision = if (raw == null) 0 else previous.revision + 1,
                    published = true,
                )
            val documents =
                encodeLeaveRequestRecords(
                        snapshot,
                        next,
                        cipher,
                        key,
                        snapshot.request.history.nextCursor?.toLong(),
                        history,
                    )
                    .toMutableList()
            if (directory != null) {
                val directoryJson =
                    decodeLeaveDocument(cipher, key, access, previous.generation, directory)
                Json.decodeFromString<LeaveDirectoryDto>(directoryJson).toReplicaDirectory(access)
                documents +=
                    encodeLeaveDocument(
                        cipher,
                        key,
                        next,
                        "directory",
                        next.revision,
                        "directory",
                        emptySet(),
                        directoryJson,
                    )
            }
            val sealed = encodeLeaveCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(next.generation, sealed, documents),
                )
            else
                replicas.commit(
                    ReplicaHandle(scope, partition, raw.handle.generation),
                    raw.revision,
                    sealed,
                    documents,
                    true,
                )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun pendingHistory(access: LeaveAccess, limit: Int) = safeStorageCall {
        require(limit in 1..8)
        val scope = leaveReplicaScope(access)
        val partition = leaveReplicaPartition(access)
        if (replicas.checkpoint(scope, partition) == null)
            return@safeStorageCall emptyList<LeaveHistoryWork>()
        val key =
            Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
        try {
            val tag = leavePendingHistoryTag(key, access)
            val page =
                replicas
                    .observeQueries(scope, partition, listOf(ReplicaQuery(tag, limit = limit)))
                    .first()
            val raw = page.checkpoint ?: return@safeStorageCall emptyList<LeaveHistoryWork>()
            val checkpoint = decodeLeaveCheckpoint(cipher, key, access, raw)
            if (checkpoint.recordSchema < 2 || !sameStoredLeaveScope(checkpoint.access, access))
                return@safeStorageCall emptyList<LeaveHistoryWork>()
            val work = mutableListOf<LeaveHistoryWork>()
            for (document in page.pages.single()) {
                val header =
                    decodeLeaveRequestHeader(document, raw.handle.generation, access, cipher, key)
                val request = header.snapshot.request
                val parent = replicas.document(scope, partition, "request/${request.id}")
                if (parent?.version == request.version)
                    work +=
                        LeaveHistoryWork(
                            request.id,
                            request.version,
                            header.acquisitionId,
                            requireNotNull(header.pendingHistoryAfter),
                        )
            }
            work.toList()
        } finally {
            key.fill(0)
        }
    }

    override suspend fun storeHistory(
        access: LeaveAccess,
        work: LeaveHistoryWork,
        page: LeaveHistoryPage,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(
            page.requestId == work.requestId &&
                page.requestVersion == work.requestVersion &&
                page.after == work.after
        )
        val scope = leaveReplicaScope(access)
        val partition = leaveReplicaPartition(access)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall false
        if (!raw.published) return@safeStorageCall false
        val key =
            Base64.decode(requireNotNull(credentials.read()) { "Content key is unavailable." })
        try {
            val previous = decodeLeaveCheckpoint(cipher, key, access, raw)
            if (previous.recordSchema < 2 || !sameStoredLeaveScope(previous.access, access))
                return@safeStorageCall false
            val document =
                replicas.document(scope, partition, leaveDetailId(work.requestId, "header"))
                    ?: return@safeStorageCall false
            val header =
                decodeLeaveRequestHeader(document, raw.handle.generation, access, cipher, key)
            if (
                header.snapshot.request.version != work.requestVersion ||
                    header.acquisitionId != work.acquisitionId ||
                    header.pendingHistoryAfter != work.after
            )
                return@safeStorageCall false
            val parent =
                replicas.document(scope, partition, "request/${work.requestId}")
                    ?: return@safeStorageCall false
            if (parent.version != work.requestVersion) return@safeStorageCall false
            val request = header.snapshot.request
            page.history
                .toHistoryDto()
                .toChanges(
                    work.after,
                    work.requestVersion,
                    LeaveStatus.valueOf(request.status),
                    Instant.parse(request.submittedAt),
                )
            val after = page.history.nextCursor?.toLong()
            require(after == null || after < work.after)
            val directory =
                replicas.document(scope, partition, "directory") ?: return@safeStorageCall false
            if (directory.version != raw.revision) return@safeStorageCall false
            val next = previous.copy(revision = previous.revision + 1)
            val directoryJson =
                decodeLeaveDocument(cipher, key, access, raw.handle.generation, directory)
            Json.decodeFromString<LeaveDirectoryDto>(directoryJson).toReplicaDirectory(access)
            val documents =
                listOf(
                    encodeLeaveRequestHeader(
                        header.copy(pendingHistoryAfter = after),
                        next,
                        cipher,
                        key,
                    ),
                    encodeLeaveActivityPage(page.toDto(), next, cipher, key),
                    encodeLeaveDocument(
                        cipher,
                        key,
                        next,
                        "directory",
                        next.revision,
                        "directory",
                        emptySet(),
                        directoryJson,
                    ),
                )
            val sealed = encodeLeaveCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(scope, partition, raw.handle.generation),
                raw.revision,
                sealed,
                documents,
                true,
            )
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: LeaveAccess) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        // A confirmed scope denial must not fall back to an older last-page projection.
        if (!replicas.clear(ReplicaScope(access.sessionId), leaveRequestPartition(access))) false
        else if (!replicas.clear(ReplicaScope(access.sessionId), leavePartition(access))) false
        else {
            currentCoroutineContext().ensureActive()
            replicas.clear(leaveReplicaScope(access), leaveReplicaPartition(access))
        }
    }
}
