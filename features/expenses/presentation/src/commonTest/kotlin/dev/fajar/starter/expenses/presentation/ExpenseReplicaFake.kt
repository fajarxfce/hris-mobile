@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.replica.entities.*
import dev.fajar.starter.expenses.domain.replica.policies.initialExpenseCheckpoint
import dev.fajar.starter.expenses.domain.replica.repositories.ExpenseReplicaRepository
import dev.fajar.starter.sync.domain.entities.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

data class ExpenseTestGeneration(
    val checkpoint: ExpenseCheckpoint? = null,
    val directory: ExpenseDirectory? = null,
    val entries: List<ExpenseReplicaEntry> = emptyList(),
    val history: Map<Pair<String, Long>, ExpenseHistoryPage> = emptyMap(),
)

class ExpenseReplicaFake(
    private val owns: (ExpenseAccess) -> Boolean,
    private val clearLegacy: () -> Unit,
) : ExpenseReplicaRepository {
    var current: ExpenseCheckpoint? = null
    private val staged = linkedMapOf<Pair<SyncCollection, String>, ExpenseReplicaEntry>()
    private val history = linkedMapOf<Pair<String, Long>, ExpenseHistoryPage>()
    private val pending = linkedMapOf<String, ExpenseHistoryWork>()
    val published =
        MutableStateFlow<AppResult<ExpenseTestGeneration>>(
            AppResult.Success(ExpenseTestGeneration())
        )
    val readAdmission = MutableStateFlow(ExpenseReadAdmission(null, null))
    var beforeWrite: suspend (Boolean) -> Unit = {}
    var beforeAdmit: suspend () -> Unit = {}
    var beforeAdmissionRead: suspend () -> Unit = {}
    var failCleanup = false
    var failAdmissionWrite = false
    var failNextObservation = false
    var failNextDetailObservation = false
    var observers = 0
    var directoryObservers = 0
    var admissionObservers = 0
    var writes = 0
    var clears = 0
    var begins = 0
    private var denialRevision = 0

    override fun observeAdmission(access: ExpenseAccess) = flow {
        admissionObservers++
        try {
            emitAll(readAdmission.map { AppResult.Success(it) })
        } finally {
            admissionObservers--
        }
    }

    override suspend fun admission(access: ExpenseAccess): AppResult<ExpenseReadAdmission> {
        beforeAdmissionRead()
        return AppResult.Success(readAdmission.value)
    }

    override suspend fun restrict(access: ExpenseAccess, failure: Failure): AppResult<Boolean> {
        if (!owns(access)) return AppResult.Success(false)
        readAdmission.value = ExpenseReadAdmission("denial-${++denialRevision}", failure)
        return if (failAdmissionWrite) expenseFailure("fixture.storage", FailureKind.Storage)
        else AppResult.Success(true)
    }

    override suspend fun admit(
        access: ExpenseAccess,
        expectedRevision: String?,
    ): AppResult<Boolean> {
        beforeAdmit()
        currentCoroutineContext().ensureActive()
        if (!owns(access) || readAdmission.value.revision != expectedRevision)
            return AppResult.Success(false)
        if (failAdmissionWrite) return expenseFailure("fixture.storage", FailureKind.Storage)
        readAdmission.value = ExpenseReadAdmission(null, null)
        return AppResult.Success(true)
    }

    override fun observeDirectory(access: ExpenseAccess): Flow<AppResult<ExpenseReplicaDirectory>> =
        flow {
            if (failNextObservation) {
                failNextObservation = false
                emit(expenseFailure("fixture.storage", FailureKind.Storage))
                return@flow
            }
            directoryObservers++
            try {
                emitAll(
                    published.map { result ->
                        when (result) {
                            is AppResult.Failed -> result
                            is AppResult.Success ->
                                AppResult.Success(
                                    ExpenseReplicaDirectory(
                                        result.value.checkpoint,
                                        result.value.directory,
                                    )
                                )
                        }
                    }
                )
            } finally {
                directoryObservers--
            }
        }

    override fun observe(
        access: ExpenseAccess,
        selection: ExpenseSelection,
    ): Flow<AppResult<ExpenseReplicaPage>> = flow {
        observers++
        try {
            emitAll(
                published.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val all =
                                result.value.entries
                                    .mapNotNull {
                                        (it.record as? ExpenseRecord.Claim)?.snapshot?.value?.claim
                                    }
                                    .filter {
                                        it.employeeId == selection.employeeId &&
                                            it.createdAt
                                                .toLocalDateTime(TimeZone.of(access.timezone))
                                                .let { date ->
                                                    YearMonth(date.year, date.month) ==
                                                        selection.month
                                                } &&
                                            (selection.status == null ||
                                                it.status == selection.status)
                                    }
                                    .sortedWith(
                                        compareByDescending<ExpenseClaim> { it.createdAt }
                                            .thenBy { it.id }
                                    )
                                    .filter { row ->
                                        selection.after?.let { testExpenseCursor(row) > it } ?: true
                                    }
                            val items =
                                all.take(20).map {
                                    ExpenseSummary(
                                        it.id,
                                        it.employeeId,
                                        it.draft.employeeNumber,
                                        it.draft.employeeName,
                                        it.draft.title,
                                        it.draft.total,
                                        it.draft.currency,
                                        it.createdAt,
                                        it.status,
                                        it.version,
                                    )
                                }
                            AppResult.Success(
                                ExpenseReplicaPage(
                                    result.value.checkpoint,
                                    ExpensePage(
                                        items,
                                        if (all.size > 20) testExpenseCursor(all[19]) else null,
                                    ),
                                )
                            )
                        }
                    }
                }
            )
        } finally {
            observers--
        }
    }

    override fun observeDetails(
        access: ExpenseAccess,
        id: String,
        historyAfter: Long?,
    ): Flow<AppResult<ExpenseReplicaDetails>> = flow {
        if (failNextDetailObservation) {
            failNextDetailObservation = false
            emit(expenseFailure("fixture.storage", FailureKind.Storage))
            return@flow
        }
        observers++
        try {
            emitAll(
                published.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val saved =
                                result.value.entries
                                    .mapNotNull { (it.record as? ExpenseRecord.Claim)?.snapshot }
                                    .singleOrNull { it.value.claim.id == id }
                            val progress =
                                result.value.entries
                                    .mapNotNull { (it.record as? ExpenseRecord.Payments)?.progress }
                                    .singleOrNull { it.claimId == id }
                            val current =
                                saved?.let {
                                    ExpenseSnapshot(
                                        it.access,
                                        it.generation,
                                        ExpenseView(
                                            it.value.claim,
                                            it.value.submission,
                                            it.value.history,
                                            null,
                                            progress?.attempts.orEmpty(),
                                        ),
                                        it.fetchedAt,
                                        it.refreshAfter,
                                    )
                                }
                            val selected =
                                if (historyAfter == null) current
                                else
                                    result.value.history[id to historyAfter]?.let { older ->
                                        current?.copy(
                                            value =
                                                current.value.copy(
                                                    history = older.history,
                                                    historyAfter = historyAfter,
                                                )
                                        )
                                    }
                            AppResult.Success(
                                ExpenseReplicaDetails(result.value.checkpoint, selected, current)
                            )
                        }
                    }
                }
            )
        } finally {
            observers--
        }
    }

    override suspend fun checkpoint(access: ExpenseAccess) = AppResult.Success(current)

    override suspend fun begin(
        checkpoint: ExpenseCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean> {
        if (!owns(checkpoint.access) || current?.generation != expectedGeneration)
            return AppResult.Success(false)
        begins++
        current = checkpoint
        staged.clear()
        history.clear()
        pending.clear()
        return AppResult.Success(true)
    }

    override suspend fun commit(
        previous: ExpenseCheckpoint,
        next: ExpenseCheckpoint,
        directory: ExpenseDirectory,
        entries: List<ExpenseReplicaEntry>,
    ): AppResult<Boolean> {
        beforeWrite(false)
        currentCoroutineContext().ensureActive()
        if (!owns(next.access) || current != previous) return AppResult.Success(false)
        for (entry in entries) putEntry(entry)
        current = next
        writes++
        if (next.published)
            published.value =
                AppResult.Success(
                    ExpenseTestGeneration(next, directory, staged.values.toList(), history.toMap())
                )
        return AppResult.Success(true)
    }

    private fun putEntry(entry: ExpenseReplicaEntry) {
        val key = entry.reference.collection to entry.reference.id
        val old = staged[key]
        if (old != null && old.reference.version > entry.reference.version) return
        staged[key] = entry
        if (entry.reference.collection == SyncCollection.EXPENSE_CLAIMS) {
            val snapshot = (entry.record as? ExpenseRecord.Claim)?.snapshot
            val after = snapshot?.value?.history?.nextCursor?.toLong()
            if (after == null) pending.remove(entry.reference.id)
            else
                pending[entry.reference.id] =
                    ExpenseHistoryWork(
                        entry.reference.id,
                        snapshot.value.claim.version,
                        snapshot.generation,
                        after,
                    )
        }
    }

    override suspend fun claimCheckpoint(access: ExpenseAccess) =
        AppResult.Success(writeCheckpoint())

    private fun writeCheckpoint() =
        current?.let { "${it.generation}:${it.revision}:${it.published}" }

    override suspend fun replaceClaim(
        access: ExpenseAccess,
        expectedCheckpoint: String?,
        snapshot: ExpenseSnapshot<ExpenseClaimRecord>,
        payments: ExpenseClaimPayments,
        history: ExpenseHistoryPage?,
    ): AppResult<Boolean> {
        beforeWrite(false)
        currentCoroutineContext().ensureActive()
        if (!owns(access) || writeCheckpoint() != expectedCheckpoint)
            return AppResult.Success(false)
        val previous =
            current
                ?: initialExpenseCheckpoint(access, Uuid.random().toString(), snapshot.fetchedAt)
        val next = previous.copy(revision = previous.revision + 1, published = true)
        putEntry(
            ExpenseReplicaEntry(
                SyncReference(
                    SyncCollection.EXPENSE_CLAIMS,
                    snapshot.value.claim.id,
                    snapshot.value.claim.version,
                ),
                ExpenseRecord.Claim(snapshot),
            )
        )
        putEntry(
            ExpenseReplicaEntry(
                SyncReference(SyncCollection.EXPENSE_PAYMENTS, payments.claimId, payments.version),
                ExpenseRecord.Payments(payments),
            )
        )
        history?.let { this.history[it.claimId to it.after] = it }
        current = next
        writes++
        val directory = (published.value as? AppResult.Success)?.value?.directory
        published.value =
            AppResult.Success(
                ExpenseTestGeneration(next, directory, staged.values.toList(), this.history.toMap())
            )
        return AppResult.Success(true)
    }

    override suspend fun pendingHistory(access: ExpenseAccess, limit: Int) =
        AppResult.Success(pending.values.take(limit))

    override suspend fun storeHistory(
        access: ExpenseAccess,
        work: ExpenseHistoryWork,
        page: ExpenseHistoryPage,
    ): AppResult<Boolean> {
        beforeWrite(false)
        currentCoroutineContext().ensureActive()
        val head =
            (staged[SyncCollection.EXPENSE_CLAIMS to work.claimId]?.record as? ExpenseRecord.Claim)
                ?.snapshot
        if (
            !owns(access) ||
                head?.generation != work.acquisitionId ||
                head.value.claim.version != work.claimVersion
        )
            return AppResult.Success(false)
        history[work.claimId to work.after] = page
        if (pending[work.claimId] == work) {
            if (page.history.nextCursor == null) pending.remove(work.claimId)
            else
                pending[work.claimId] =
                    work.copy(after = requireNotNull(page.history.nextCursor).toLong())
        }
        val next = requireNotNull(current).let { it.copy(revision = it.revision + 1) }
        current = next
        writes++
        val previous = (published.value as AppResult.Success).value
        published.value =
            AppResult.Success(previous.copy(checkpoint = next, history = history.toMap()))
        return AppResult.Success(true)
    }

    override suspend fun clear(access: ExpenseAccess): AppResult<Boolean> {
        beforeWrite(true)
        currentCoroutineContext().ensureActive()
        if (!owns(access)) return AppResult.Success(false)
        if (failCleanup) return expenseFailure("fixture.storage", FailureKind.Storage)
        clears++
        clearLegacy()
        current = null
        staged.clear()
        history.clear()
        pending.clear()
        published.value = AppResult.Success(ExpenseTestGeneration())
        return AppResult.Success(true)
    }

    fun seed(snapshot: ExpenseSnapshot<ExpenseView>) {
        val claim = snapshot.value.claim
        putEntry(
            ExpenseReplicaEntry(
                SyncReference(SyncCollection.EXPENSE_CLAIMS, claim.id, claim.version),
                ExpenseRecord.Claim(
                    ExpenseSnapshot(
                        snapshot.access,
                        snapshot.generation,
                        ExpenseClaimRecord(
                            claim,
                            snapshot.value.submission,
                            snapshot.value.history,
                        ),
                        snapshot.fetchedAt,
                        snapshot.refreshAfter,
                    )
                ),
            )
        )
        putEntry(
            ExpenseReplicaEntry(
                SyncReference(SyncCollection.EXPENSE_PAYMENTS, claim.id, 0),
                ExpenseRecord.Payments(ExpenseClaimPayments(claim.id, 0, snapshot.value.payments)),
            )
        )
        val next =
            (current ?: initialExpenseCheckpoint(snapshot.access, "fixture", snapshot.fetchedAt))
                .let { it.copy(revision = it.revision + 1, published = true) }
        current = next
        val previous = (published.value as? AppResult.Success)?.value ?: ExpenseTestGeneration()
        published.value =
            AppResult.Success(previous.copy(checkpoint = next, entries = staged.values.toList()))
    }
}

fun testExpenseCursor(claim: ExpenseClaim) =
    "${(kotlin.time.Instant.parse("2201-01-01T00:00:00Z").toEpochMilliseconds() - claim.createdAt.toEpochMilliseconds()).toString().padStart(15, '0')}/${claim.id}"
