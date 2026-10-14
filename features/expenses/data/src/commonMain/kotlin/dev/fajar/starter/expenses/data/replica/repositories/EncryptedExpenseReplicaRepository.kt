@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.encoding.expensePartition
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.data.replica.datasources.ExpenseReadAdmissionDataSource
import dev.fajar.starter.expenses.data.replica.dto.ExpenseReadAdmissionDto
import dev.fajar.starter.expenses.data.replica.encoding.*
import dev.fajar.starter.expenses.data.replica.mappers.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.initialExpenseCheckpoint
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedExpenseReplicaRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("expense-replica-content-key") private val credentials: CredentialStore,
    private val admissions: ExpenseReadAdmissionDataSource,
    @Named("expense-content-key-creation") private val keyCreation: Mutex = Mutex(),
) : ExpenseReplicaRepository {
    private val admissionWrites = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAdmission(access: ExpenseAccess) =
        admissions
            .observe(expenseAdmissionBufferKey(access))
            .flatMapLatest { pending ->
                if (pending != null)
                    safeStorageFlow(flowOf(pending), mapValue = { it.toReadAdmission() })
                else
                    safeStorageFlow(
                        replicas.observeCheckpoint(
                            expenseReplicaScope(access),
                            expenseAdmissionPartition(access),
                        ),
                        mapValue = { raw ->
                            if (raw == null) ExpenseReadAdmission(null, null)
                            else {
                                val key = Base64.decode(requireNotNull(credentials.read()))
                                try {
                                    decodeExpenseReadAdmission(cipher, key, access, raw)
                                        .toReadAdmission()
                                } finally {
                                    key.fill(0)
                                }
                            }
                        },
                    )
            }
            .distinctUntilChanged()

    override suspend fun admission(access: ExpenseAccess) = observeAdmission(access).first()

    override suspend fun restrict(access: ExpenseAccess, failure: Failure) = safeStorageCall {
        val bufferKey = expenseAdmissionBufferKey(access)
        val dto = ExpenseReadAdmissionDto(Uuid.random().toString(), failure.kind.name, failure.code)
        dto.toReadAdmission()
        // Publish the confirmed denial even when the following durable write cannot complete.
        admissions.write(bufferKey, dto)
        admissionWrites.withLock {
            val scope = expenseReplicaScope(access)
            val partition = expenseAdmissionPartition(access)
            val previous = replicas.checkpoint(scope, partition)
            val key =
                keyCreation.withLock {
                    readOrCreateExpenseReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeExpenseReadAdmission(cipher, key, access, dto)
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

    override suspend fun admit(access: ExpenseAccess, expectedRevision: String?) = safeStorageCall {
        admissionWrites.withLock {
            val bufferKey = expenseAdmissionBufferKey(access)
            val scope = expenseReplicaScope(access)
            val partition = expenseAdmissionPartition(access)
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

    override fun observeDirectory(access: ExpenseAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                expenseReplicaScope(access),
                expenseReplicaPartition(access),
                "directory",
            ),
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) ExpenseReplicaDirectory(null, null)
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodeExpenseCheckpoint(cipher, key, access, raw)
                        val document = page.documents.singleOrNull()
                        val directory =
                            document?.let {
                                require(
                                    it.id == "directory" &&
                                        it.sortKey == "directory" &&
                                        it.tags.isEmpty() &&
                                        it.version == raw.revision
                                )
                                Json.decodeFromString<ExpenseDirectoryDto>(
                                        decodeExpenseDocument(
                                            cipher,
                                            key,
                                            access,
                                            raw.handle.generation,
                                            it,
                                        )
                                    )
                                    .toReplicaDirectory(access)
                            }
                        require(directory != null || checkpoint.refreshedAt == null)
                        ExpenseReplicaDirectory(checkpoint, directory)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override fun observe(access: ExpenseAccess, selection: ExpenseSelection) =
        safeStorageFlow(
            observeIndexedRecords(access) { key ->
                require(validateExpenseSelection(selection) is AppResult.Success)
                require(selection.after?.let(::isExpenseLocalCursor) != false)
                listOf(
                    ReplicaQuery(
                        expenseSelectionTag(key, access, selection),
                        selection.after,
                        EXPENSE_PAGE_SIZE + 1,
                    )
                )
            },
            mapValue = { page ->
                val raw = page.checkpoint
                if (raw == null) ExpenseReplicaPage(null, ExpensePage(emptyList()))
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        val checkpoint = decodeExpenseCheckpoint(cipher, key, access, raw)
                        val documents = page.pages.single()
                        require(
                            documents.size <= EXPENSE_PAGE_SIZE + 1 &&
                                documents.zipWithNext().all { (a, b) -> a.sortKey < b.sortKey }
                        )
                        val summaries =
                            documents.map { document ->
                                val value =
                                    Json.decodeFromString<ExpenseSummaryDto>(
                                            decodeExpenseDocument(
                                                cipher,
                                                key,
                                                access,
                                                raw.handle.generation,
                                                document,
                                            )
                                        )
                                        .toSummary()
                                require(
                                    document.id == "summary/${value.id}" &&
                                        document.version == value.version &&
                                        document.sortKey == expenseSummarySortKey(value)
                                )
                                require(
                                    document.tags ==
                                        expenseSummaryTags(
                                            key,
                                            access,
                                            value,
                                            checkpoint.access.timezone,
                                        )
                                )
                                require(
                                    expenseSelectionTag(key, access, selection) in document.tags
                                )
                                require(selection.after?.let { document.sortKey > it } != false)
                                value
                            }
                        val items = summaries.take(EXPENSE_PAGE_SIZE)
                        ExpenseReplicaPage(
                            checkpoint,
                            ExpensePage(
                                items,
                                if (summaries.size > items.size) expenseSummarySortKey(items.last())
                                else null,
                            ),
                        )
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override fun observeDetails(access: ExpenseAccess, id: String, historyAfter: Long?) =
        safeStorageFlow(
            observeIndexedRecords(access) { key ->
                require(
                    validateExpenseId(id) is AppResult.Success &&
                        validateExpenseHistoryCursor(historyAfter) is AppResult.Success
                )
                buildList {
                    add(ReplicaQuery(expenseClaimTag(key, access, id), limit = 19))
                    historyAfter?.let {
                        add(ReplicaQuery(expenseHistoryTag(key, access, id, it), limit = 1))
                    }
                }
            },
            mapValue = { page ->
                if (page.checkpoint == null) ExpenseReplicaDetails(null, null)
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        decodeExpenseReplicaDetails(page, access, id, historyAfter, cipher, key)
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeIndexedRecords(
        access: ExpenseAccess,
        queries: suspend (ByteArray) -> List<ReplicaQuery>,
    ): Flow<ReplicaQueryPage> =
        replicas
            .observeCheckpoint(expenseReplicaScope(access), expenseReplicaPartition(access))
            .map { it != null }
            .distinctUntilChanged()
            .flatMapLatest { retained ->
                if (!retained) flowOf(ReplicaQueryPage(null, emptyList()))
                else
                    flow {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        val selected =
                            try {
                                queries(key)
                            } finally {
                                key.fill(0)
                            }
                        emitAll(
                            replicas.observeQueries(
                                expenseReplicaScope(access),
                                expenseReplicaPartition(access),
                                selected,
                            )
                        )
                    }
            }

    override suspend fun checkpoint(access: ExpenseAccess) = safeStorageCall {
        val raw =
            replicas.checkpoint(expenseReplicaScope(access), expenseReplicaPartition(access))
                ?: return@safeStorageCall null
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            decodeExpenseCheckpoint(cipher, key, access, raw)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun begin(checkpoint: ExpenseCheckpoint, expectedGeneration: String?) =
        safeStorageCall {
            currentCoroutineContext().ensureActive()
            require(checkpoint.revision == 0L && !checkpoint.published)
            val key =
                keyCreation.withLock {
                    readOrCreateExpenseReplicaKey(replicas, cipher, credentials)
                }
            try {
                val sealed = encodeExpenseCheckpoint(cipher, key, checkpoint)
                currentCoroutineContext().ensureActive()
                replicas.begin(
                    expenseReplicaScope(checkpoint.access),
                    expenseReplicaPartition(checkpoint.access),
                    checkpoint.generation,
                    sealed,
                    expectedGeneration,
                )
            } finally {
                key.fill(0)
            }
        }

    override suspend fun commit(
        previous: ExpenseCheckpoint,
        next: ExpenseCheckpoint,
        directory: ExpenseDirectory,
        entries: List<ExpenseReplicaEntry>,
    ) = safeStorageCall {
        require(
            next.generation == previous.generation &&
                next.revision == previous.revision + 1 &&
                next.access == previous.access &&
                (!previous.published || next.published)
        )
        val batch = entries.toList()
        require(
            batch.size <= 4 &&
                batch.distinctBy { it.reference.collection to it.reference.id }.size == batch.size
        )
        val dto = directory.toDto()
        dto.toReplicaDirectory(previous.access)
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val documents = mutableListOf<ReplicaDocument>()
            for (entry in batch) documents += encodeExpenseReplicaEntry(cipher, key, next, entry)
            documents +=
                encodeExpenseDocument(
                    cipher,
                    key,
                    next,
                    "directory",
                    next.revision,
                    "directory",
                    emptySet(),
                    Json.encodeToString(dto),
                )
            val sealed = encodeExpenseCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(
                ReplicaHandle(
                    expenseReplicaScope(previous.access),
                    expenseReplicaPartition(previous.access),
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

    override suspend fun claimCheckpoint(access: ExpenseAccess) = safeStorageCall {
        expenseWriteCheckpoint(
            replicas.checkpoint(expenseReplicaScope(access), expenseReplicaPartition(access))
        )
    }

    override suspend fun replaceClaim(
        access: ExpenseAccess,
        expectedCheckpoint: String?,
        snapshot: ExpenseSnapshot<ExpenseClaimRecord>,
        payments: ExpenseClaimPayments,
        history: ExpenseHistoryPage?,
    ) = safeStorageCall {
        val scope = expenseReplicaScope(access)
        val partition = expenseReplicaPartition(access)
        val raw = replicas.checkpoint(scope, partition)
        if (expenseWriteCheckpoint(raw) != expectedCheckpoint) return@safeStorageCall false
        if (
            raw != null &&
                !raw.published &&
                replicas.observeDocument(scope, partition, "directory").first().checkpoint != null
        )
            return@safeStorageCall false
        val key =
            keyCreation.withLock { readOrCreateExpenseReplicaKey(replicas, cipher, credentials) }
        try {
            val previous =
                if (raw == null)
                    initialExpenseCheckpoint(access, snapshot.generation, snapshot.fetchedAt)
                else decodeExpenseCheckpoint(cipher, key, access, raw)
            if (!sameStoredExpenseScope(previous.access, access)) return@safeStorageCall false
            val id = snapshot.value.claim.id
            val parent = replicas.document(scope, partition, "claim/$id/head")
            if (parent != null && parent.version > snapshot.value.claim.version)
                return@safeStorageCall false
            val next =
                previous.copy(
                    revision = if (raw == null) 0 else previous.revision + 1,
                    published = true,
                )
            val documents = encodeExpenseClaimRecords(snapshot, next, cipher, key).toMutableList()
            require(payments.claimId == id)
            documents +=
                encodeExpenseReplicaEntry(
                    cipher,
                    key,
                    next,
                    ExpenseReplicaEntry(
                        SyncReference(SyncCollection.EXPENSE_PAYMENTS, id, payments.version),
                        ExpenseRecord.Payments(payments),
                    ),
                )
            if (history != null) {
                require(
                    history.claimId == id && history.claimVersion == snapshot.value.claim.version
                )
                documents += encodeExpenseHistoryPage(history, next, cipher, key)
            }
            documents += copyDirectory(access, previous, next, key)
            val sealed = encodeExpenseCheckpoint(cipher, key, next)
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

    /** A metadata-only update retains the original review lease and encrypted claim chunks. */
    override suspend fun storeHistory(
        access: ExpenseAccess,
        work: ExpenseHistoryWork,
        page: ExpenseHistoryPage,
    ) = safeStorageCall {
        require(
            page.claimId == work.claimId &&
                page.claimVersion == work.claimVersion &&
                page.after == work.after
        )
        val scope = expenseReplicaScope(access)
        val partition = expenseReplicaPartition(access)
        val raw = replicas.checkpoint(scope, partition) ?: return@safeStorageCall false
        if (!raw.published) return@safeStorageCall false
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val previous = decodeExpenseCheckpoint(cipher, key, access, raw)
            val document =
                replicas.document(scope, partition, "claim/${work.claimId}/head")
                    ?: return@safeStorageCall false
            val header =
                decodeExpenseClaimHeader(document, access, previous.generation, cipher, key)
            if (
                header.claimVersion != work.claimVersion ||
                    header.acquisitionId != work.acquisitionId
            )
                return@safeStorageCall false
            page.toDto().toHistoryPage()
            val after = page.history.nextCursor?.toLong()
            require(after == null || after < work.after)
            val next = previous.copy(revision = previous.revision + 1)
            val pending =
                if (header.pendingHistoryAfter == work.after) after else header.pendingHistoryAfter
            val documents =
                listOf(
                    encodeExpenseClaimHeader(
                        header.copy(pendingHistoryAfter = pending),
                        next,
                        cipher,
                        key,
                    ),
                    encodeExpenseHistoryPage(page, next, cipher, key),
                ) + copyDirectory(access, previous, next, key)
            val sealed = encodeExpenseCheckpoint(cipher, key, next)
            currentCoroutineContext().ensureActive()
            replicas.commit(raw.handle, raw.revision, sealed, documents, true)
        } finally {
            key.fill(0)
        }
    }

    override suspend fun pendingHistory(access: ExpenseAccess, limit: Int) = safeStorageCall {
        require(limit in 1..2)
        if (
            replicas.checkpoint(expenseReplicaScope(access), expenseReplicaPartition(access)) ==
                null
        )
            return@safeStorageCall emptyList<ExpenseHistoryWork>()
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            val page =
                replicas
                    .observeQueries(
                        expenseReplicaScope(access),
                        expenseReplicaPartition(access),
                        listOf(ReplicaQuery(expensePendingHistoryTag(key, access), limit = limit)),
                    )
                    .first()
            val raw = page.checkpoint ?: return@safeStorageCall emptyList<ExpenseHistoryWork>()
            decodeExpenseCheckpoint(cipher, key, access, raw)
            page.pages.single().map {
                val header =
                    decodeExpenseClaimHeader(it, access, raw.handle.generation, cipher, key)
                ExpenseHistoryWork(
                    header.claimId,
                    header.claimVersion,
                    header.acquisitionId,
                    requireNotNull(header.pendingHistoryAfter),
                )
            }
        } finally {
            key.fill(0)
        }
    }

    /** Restamp unchanged directory metadata in the same transaction as a checkpoint update. */
    private suspend fun copyDirectory(
        access: ExpenseAccess,
        previous: ExpenseCheckpoint,
        next: ExpenseCheckpoint,
        key: ByteArray,
    ): List<ReplicaDocument> {
        val document =
            replicas.document(
                expenseReplicaScope(access),
                expenseReplicaPartition(access),
                "directory",
            )
        require(document != null || previous.refreshedAt == null)
        if (document == null) return emptyList()
        require(
            document.version == previous.revision &&
                document.tags.isEmpty() &&
                document.sortKey == "directory"
        )
        val json = decodeExpenseDocument(cipher, key, access, previous.generation, document)
        Json.decodeFromString<ExpenseDirectoryDto>(json).toReplicaDirectory(access)
        return listOf(
            encodeExpenseDocument(
                cipher,
                key,
                next,
                "directory",
                next.revision,
                "directory",
                emptySet(),
                json,
            )
        )
    }

    override suspend fun clear(access: ExpenseAccess) = safeStorageCall {
        // Confirmed denial also removes the preceding last-view migration fallback.
        for (view in listOf("overview", "details")) {
            if (!replicas.clear(ReplicaScope(access.sessionId), expensePartition(access, view)))
                return@safeStorageCall false
        }
        currentCoroutineContext().ensureActive()
        replicas.clear(expenseReplicaScope(access), expenseReplicaPartition(access))
    }
}
