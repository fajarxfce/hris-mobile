package dev.fajar.starter.workforce.data.overtime.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.sync.domain.entities.SyncCollection
import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.encoding.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single(binds = [OvertimeReplicaRepository::class, OvertimeRequestReplicaRepository::class])
class EncryptedOvertimeReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("overtime-replica-content-key") private val credentials: CredentialStore,
    @Named("workspace-content-key") private val legacyCredentials: AtomicCredentialStore,
) : OvertimeReplicaRepository {
    private val keyCreation = Mutex()

    override fun observeDirectory(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                overtimeReplicaScope(access),
                overtimeReplicaPartition(access),
                "directory",
            ),
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) OvertimeReplicaDirectory(null, null)
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodeOvertimeCheckpoint(cipher, key, access, raw)
                        if (checkpoint.refreshedAt == null) OvertimeReplicaDirectory(null, null)
                        else {
                            val document = page.documents.single()
                            require(document.id == "directory" && document.version == raw.revision)
                            val directory =
                                Json.decodeFromString<WorkforceDirectoryDto>(
                                        decodeOvertimeDocument(
                                            cipher,
                                            key,
                                            access,
                                            raw.handle.generation,
                                            document,
                                        )
                                    )
                                    .toWorkforceReplicaDirectory(access)
                            OvertimeReplicaDirectory(checkpoint, directory)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override fun observe(access: WorkforceAccess, selection: OvertimeSelection) =
        safeStorageFlow(
            flow {
                val employeeId = requireNotNull(selection.employeeId)
                val period = requireNotNull(selection.period)
                val key = Base64.decode(requireNotNull(credentials.read()))
                val tag =
                    try {
                        overtimeReplicaTags(
                                key,
                                access,
                                employeeId,
                                listOf(listOf("requests", selection.status?.name ?: "ALL")),
                            )
                            .single()
                    } finally {
                        key.fill(0)
                    }
                emitAll(
                    replicas.observeQueries(
                        overtimeReplicaScope(access),
                        overtimeReplicaPartition(access),
                        listOf(
                            ReplicaQuery(
                                tag,
                                selection.requestAfter ?: ("r/" + period.from + "/"),
                                21,
                            )
                        ),
                    )
                )
            },
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) OvertimeReplicaPage(null, OvertimePage(emptyList()))
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodeOvertimeCheckpoint(cipher, key, access, raw)
                        val period = requireNotNull(selection.period)
                        val rows =
                            page.pages.single().map { document ->
                                val summary =
                                    Json.decodeFromString<OvertimeSummaryDto>(
                                            decodeOvertimeDocument(
                                                cipher,
                                                key,
                                                access,
                                                raw.handle.generation,
                                                document,
                                            )
                                        )
                                        .toSummary()
                                require(
                                    document.id == "summary/" + summary.id &&
                                        document.version == summary.version
                                )
                                require(
                                    document.sortKey == summary.replicaSortKey() &&
                                        summary.employeeId == selection.employeeId
                                )
                                require(document.tags == overtimeSummaryTags(key, access, summary))
                                require(
                                    selection.status == null || summary.status == selection.status
                                )
                                require(summary.workDate >= period.from)
                                require(
                                    selection.requestAfter == null ||
                                        document.sortKey > requireNotNull(selection.requestAfter)
                                )
                                summary
                            }
                        require(
                            rows.zipWithNext().all { (a, b) ->
                                a.replicaSortKey() < b.replicaSortKey()
                            }
                        )
                        // Date order makes end-of-period trimming bounded to this one indexed page.
                        val visible = rows.takeWhile { it.workDate <= period.until }
                        OvertimeReplicaPage(
                            checkpoint,
                            OvertimePage(
                                visible.take(20),
                                if (visible.size > 20) visible[19].replicaSortKey() else null,
                            ),
                        )
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun checkpoint(access: WorkforceAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(overtimeReplicaScope(access), overtimeReplicaPartition(access))
                ?: return@safeStorageCall null
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            decodeOvertimeCheckpoint(cipher, key, access, raw)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun begin(checkpoint: OvertimeCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    readOrCreateOvertimeReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeOvertimeCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    overtimeReplicaScope(checkpoint.access),
                    overtimeReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: OvertimeCheckpoint,
        next: OvertimeCheckpoint,
        directory: WorkforceDirectory,
        entries: List<OvertimeReplicaEntry>,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(next.generation == previous.generation && next.revision == previous.revision + 1)
        require(next.access == previous.access && (!previous.published || next.published))
        val access = previous.access
        directory.toDto().toWorkforceReplicaDirectory(access)
        val batch = entries.toList()
        require(batch.size <= 8 && batch.distinctBy { it.reference.id }.size == batch.size)
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val documents = mutableListOf<ReplicaDocument>()
            for (entry in batch) {
                val reference = entry.reference
                require(reference.collection == SyncCollection.OVERTIME_REQUESTS)
                val record = entry.record
                if (record == null) {
                    documents +=
                        ReplicaDocument(
                            "request/" + reference.id,
                            reference.version,
                            "request/" + reference.id,
                            null,
                        )
                    documents +=
                        ReplicaDocument(
                            "summary/" + reference.id,
                            reference.version,
                            "deleted/" + reference.id,
                            null,
                        )
                } else {
                    require(
                        reference.id == record.request.id &&
                            reference.version == record.request.version
                    )
                    require(directory.items.any { it.id == record.request.employeeId })
                    val acquired = requireNotNull(entry.acquiredAt)
                    val selection =
                        OvertimeRequestSelection(record.request.id, record.request.employeeId)
                    val snapshot =
                        OvertimeSnapshot(
                            access,
                            Json.encodeToString(
                                listOf(next.generation, next.revision.toString(), reference.id)
                            ),
                            OvertimeRequestView(selection, record),
                            acquired,
                            acquired + OVERTIME_FRESHNESS_PERIOD,
                        )
                    documents += encodeOvertimeRequestRecords(snapshot, next, cipher, key)
                }
            }
            documents +=
                encodeOvertimeDocument(
                    cipher,
                    key,
                    next,
                    "directory",
                    next.revision,
                    "directory",
                    emptySet(),
                    Json.encodeToString(directory.toDto()),
                )
            val sealed = encodeOvertimeCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    overtimeReplicaScope(access),
                    overtimeReplicaPartition(access),
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
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>> =
        safeStorageFlow(
                replicas.observeDocument(
                    overtimeReplicaScope(access),
                    overtimeReplicaPartition(access),
                    "request/" + selection.requestId,
                ),
                mapValue = { page ->
                    val raw = page.checkpoint
                    if (raw == null) null
                    else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            val checkpoint = decodeOvertimeCheckpoint(cipher, key, access, raw)
                            val snapshot =
                                page.documents.singleOrNull()?.let {
                                    decodeOvertimeRequestHeader(
                                            access,
                                            raw.handle.generation,
                                            it,
                                            cipher,
                                            key,
                                        )
                                        .toReplicaSnapshot(
                                            access,
                                            selection.copy(historyAfter = null),
                                        )
                                }
                            checkpoint to snapshot
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
                        val content = result.value
                        when {
                            content == null ||
                                (content.second == null && content.first.refreshedAt == null) ->
                                observeLegacyRequest(access, selection)
                            content.second == null -> flowOf(AppResult.Success(null))
                            selection.historyAfter == null ->
                                flowOf(AppResult.Success(content.second))
                            else ->
                                observeHistory(
                                    access,
                                    selection,
                                    content.first.generation,
                                    requireNotNull(content.second).value.details.request.version,
                                )
                        }
                    }
                }
            }

    private fun observeLegacyRequest(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>> =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                overtimePartition(access, "request"),
                "request",
            ),
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) null
                else {
                    val key = Base64.decode(requireNotNull(legacyCredentials.read()))
                    try {
                        decodeLegacyOvertimeRequest(page, access, cipher, key).takeIf {
                            it.value.selection.requestId == selection.requestId &&
                                it.value.selection.employeeId == selection.employeeId
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    private fun observeHistory(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        generation: String,
        version: Long,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>> =
        safeStorageFlow(
            flow {
                val key = Base64.decode(requireNotNull(credentials.read()))
                val tags =
                    try {
                        listOf(
                            overtimeRequestTag(key, access, selection.requestId, "request"),
                            overtimeRequestTag(
                                key,
                                access,
                                selection.requestId,
                                "activity/" + selection.historyAfter,
                            ),
                        )
                    } finally {
                        key.fill(0)
                    }
                emitAll(
                    replicas
                        .observeQueries(
                            overtimeReplicaScope(access),
                            overtimeReplicaPartition(access),
                            listOf(
                                ReplicaQuery(tags[0], limit = 1),
                                ReplicaQuery(tags[1], limit = 1),
                            ),
                        )
                        .filter {
                            it.checkpoint?.handle?.generation == generation &&
                                it.pages.firstOrNull()?.singleOrNull()?.version == version
                        }
                )
            },
            mapValue = { page ->
                val raw = requireNotNull(page.checkpoint)
                val key = Base64.decode(requireNotNull(credentials.read()))
                try {
                    val header =
                        decodeOvertimeRequestHeader(
                            access,
                            raw.handle.generation,
                            page.pages[0].single(),
                            cipher,
                            key,
                        )
                    val document = page.pages[1].singleOrNull()
                    val history =
                        if (document != null)
                            decodeOvertimeHistoryPage(
                                access,
                                raw.handle.generation,
                                selection,
                                document,
                                cipher,
                                key,
                            )
                        else {
                            // Preserve an acquired legacy activity page while its replica is
                            // filled.
                            val legacy =
                                replicas
                                    .observeDocument(
                                        ReplicaScope(access.sessionId),
                                        overtimePartition(access, "request"),
                                        "request",
                                    )
                                    .first()
                            val saved =
                                if (legacy.checkpoint == null) null
                                else {
                                    val legacyKey =
                                        Base64.decode(requireNotNull(legacyCredentials.read()))
                                    try {
                                        decodeLegacyOvertimeRequest(
                                            legacy,
                                            access,
                                            cipher,
                                            legacyKey,
                                        )
                                    } finally {
                                        legacyKey.fill(0)
                                    }
                                }
                            saved?.toLegacyOvertimeHistory(selection, version)
                        }
                    history?.let { header.toReplicaSnapshot(access, selection, it) }
                } finally {
                    key.fill(0)
                }
            },
        )

    override suspend fun requestCheckpoint(access: WorkforceAccess) = safeStorageCall {
        overtimeWriteCheckpoint(
            replicas.checkpoint(overtimeReplicaScope(access), overtimeReplicaPartition(access))
        )
    }

    override suspend fun replaceRequest(
        access: WorkforceAccess,
        requestId: String,
        expectedCheckpoint: String?,
        snapshot: OvertimeSnapshot<OvertimeRequestView>?,
        history: OvertimeHistoryPage?,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        val scope = overtimeReplicaScope(access)
        val partition = overtimeReplicaPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (overtimeWriteCheckpoint(raw) != expectedCheckpoint) return@safeStorageCall false
        if (snapshot == null) {
            if (
                !replicas.clear(
                    ReplicaScope(access.sessionId),
                    overtimePartition(access, "request"),
                )
            )
                return@safeStorageCall false
            if (
                !replicas.clear(
                    ReplicaScope(access.sessionId),
                    overtimePartition(access, "overview"),
                )
            )
                return@safeStorageCall false
            currentCoroutineContext().ensureActive()
            return@safeStorageCall replicas.clear(scope, partition)
        }
        if (raw != null && !raw.published) {
            val visible = replicas.observeDocument(scope, partition, "directory").first()
            if (visible.checkpoint != null) return@safeStorageCall false
        }
        require(
            snapshot.value.details.request.id == requestId &&
                snapshot.value.selection.historyAfter == null
        )
        require(sameWorkforceScope(access, snapshot.access))
        val key =
            keyCreation.withLock { readOrCreateOvertimeReplicaKey(replicas, cipher, credentials) }
        try {
            val previous =
                if (raw == null)
                    initialOvertimeCheckpoint(access, snapshot.generation, snapshot.fetchedAt)
                else decodeOvertimeCheckpoint(cipher, key, access, raw)
            if (!sameStoredOvertimeScope(previous.access, access)) return@safeStorageCall false
            val parent = replicas.document(scope, partition, "request/" + requestId)
            if (parent != null && parent.version > snapshot.value.details.request.version)
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
                encodeOvertimeRequestRecords(snapshot, next, cipher, key, history).toMutableList()
            if (directory != null) {
                val json =
                    decodeOvertimeDocument(cipher, key, access, previous.generation, directory)
                Json.decodeFromString<WorkforceDirectoryDto>(json)
                    .toWorkforceReplicaDirectory(access)
                documents +=
                    encodeOvertimeDocument(
                        cipher,
                        key,
                        next,
                        "directory",
                        next.revision,
                        "directory",
                        emptySet(),
                        json,
                    )
            }
            val sealed = encodeOvertimeCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            if (raw == null)
                replicas.replace(
                    scope,
                    partition,
                    null,
                    ReplicaSnapshot(next.generation, sealed, documents),
                )
            else replicas.commit(raw.handle, raw.revision, sealed, documents, true)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun pendingHistory(access: WorkforceAccess, limit: Int) = safeStorageCall {
        require(limit in 1..8)
        val scope = overtimeReplicaScope(access)
        val partition = overtimeReplicaPartition(access)
        if (replicas.checkpoint(scope, partition) == null)
            return@safeStorageCall emptyList<OvertimeHistoryWork>()
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val tag = overtimePendingHistoryTag(key, access)
            val page =
                replicas
                    .observeQueries(scope, partition, listOf(ReplicaQuery(tag, limit = limit)))
                    .first()
            val raw = page.checkpoint ?: return@safeStorageCall emptyList<OvertimeHistoryWork>()
            val checkpoint = decodeOvertimeCheckpoint(cipher, key, access, raw)
            if (!sameStoredOvertimeScope(checkpoint.access, access))
                return@safeStorageCall emptyList<OvertimeHistoryWork>()
            val work = mutableListOf<OvertimeHistoryWork>()
            for (document in page.pages.single()) {
                val header =
                    decodeOvertimeRequestHeader(
                        access,
                        raw.handle.generation,
                        document,
                        cipher,
                        key,
                    )
                val request = header.snapshot.value.request
                work +=
                    OvertimeHistoryWork(
                        request.id,
                        request.employeeId,
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
        access: WorkforceAccess,
        work: OvertimeHistoryWork,
        page: OvertimeHistoryPage,
    ) = safeStorageCall {
        currentCoroutineContext().ensureActive()
        require(
            page.requestId == work.requestId &&
                page.requestVersion == work.requestVersion &&
                page.after == work.after
        )
        val scope = overtimeReplicaScope(access)
        val partition = overtimeReplicaPartition(access)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall false
        if (!raw.published) return@safeStorageCall false
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val previous = decodeOvertimeCheckpoint(cipher, key, access, raw)
            if (!sameStoredOvertimeScope(previous.access, access)) return@safeStorageCall false
            val document =
                replicas.document(scope, partition, "request/" + work.requestId)
                    ?: return@safeStorageCall false
            val header =
                decodeOvertimeRequestHeader(access, raw.handle.generation, document, cipher, key)
            val request = header.snapshot.value.toView().details.request
            if (
                request.version != work.requestVersion ||
                    request.employeeId != work.employeeId ||
                    header.acquisitionId != work.acquisitionId ||
                    header.pendingHistoryAfter != work.after
            )
                return@safeStorageCall false
            val dto = page.toDto()
            dto.history.toHistory(request, work.after)
            val after = page.history.nextCursor?.toLong()
            require(after == null || after < work.after)
            val directory =
                replicas.document(scope, partition, "directory") ?: return@safeStorageCall false
            if (directory.version != raw.revision) return@safeStorageCall false
            val next = previous.copy(revision = previous.revision + 1)
            val json = decodeOvertimeDocument(cipher, key, access, raw.handle.generation, directory)
            Json.decodeFromString<WorkforceDirectoryDto>(json).toWorkforceReplicaDirectory(access)
            val documents =
                listOf(
                    encodeOvertimeRequestHeader(
                        header.copy(pendingHistoryAfter = after),
                        next,
                        cipher,
                        key,
                    ),
                    encodeOvertimeHistoryPage(dto, next, cipher, key),
                    encodeOvertimeDocument(
                        cipher,
                        key,
                        next,
                        "directory",
                        next.revision,
                        "directory",
                        emptySet(),
                        json,
                    ),
                )
            val sealed = encodeOvertimeCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(raw.handle, raw.revision, sealed, documents, true)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun clear(access: WorkforceAccess) = safeStorageCall {
        if (!replicas.clear(ReplicaScope(access.sessionId), overtimePartition(access, "request")))
            false
        else if (
            !replicas.clear(ReplicaScope(access.sessionId), overtimePartition(access, "overview"))
        )
            false
        else {
            currentCoroutineContext().ensureActive()
            replicas.clear(overtimeReplicaScope(access), overtimeReplicaPartition(access))
        }
    }
}
