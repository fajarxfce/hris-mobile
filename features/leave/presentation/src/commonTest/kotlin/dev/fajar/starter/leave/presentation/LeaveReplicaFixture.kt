package dev.fajar.starter.leave.presentation

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import dev.fajar.starter.leave.domain.repositories.LeaveReplicaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

/**
 * Domain-port probe. Real encryption, indexing and transaction behavior have separate data tests.
 */
class LeaveReplicaFixture : LeaveReplicaRepository {
    val readAdmission =
        MutableStateFlow<AppResult<LeaveReadAdmission>>(
            AppResult.Success(LeaveReadAdmission(null, null))
        )
    var admissionObservers = 0
    var restrictions = 0
    var beforeAdmissionRead: suspend () -> Unit = {}
    var beforeAdmit: suspend () -> Unit = {}
    var failAdmissionWrite = false

    override fun observeAdmission(access: LeaveAccess): Flow<AppResult<LeaveReadAdmission>> = flow {
        admissionObservers++
        try {
            emitAll(readAdmission)
        } finally {
            admissionObservers--
        }
    }

    override suspend fun admission(access: LeaveAccess): AppResult<LeaveReadAdmission> {
        beforeAdmissionRead()
        return readAdmission.value
    }

    override suspend fun restrict(access: LeaveAccess, failure: Failure): AppResult<Boolean> {
        restrictions++
        readAdmission.value =
            AppResult.Success(
                LeaveReadAdmission(
                    "00000000-0000-4000-8000-${restrictions.toString().padStart(12, '0')}",
                    failure,
                )
            )
        return if (failAdmissionWrite) leaveFailure("client.storage", FailureKind.Storage)
        else AppResult.Success(true)
    }

    override suspend fun admit(access: LeaveAccess, expectedRevision: String?): AppResult<Boolean> {
        beforeAdmit()
        val current = readAdmission.value
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value.revision != expectedRevision)
            return AppResult.Success(false)
        if (failAdmissionWrite) return leaveFailure("client.storage", FailureKind.Storage)
        readAdmission.value = AppResult.Success(LeaveReadAdmission(null, null))
        return AppResult.Success(true)
    }

    data class Published(
        val checkpoint: LeaveCheckpoint?,
        val directory: LeaveDirectory?,
        val entries: List<LeaveReplicaEntry>,
    )

    val published =
        MutableStateFlow<AppResult<Published>>(
            AppResult.Success(Published(null, null, emptyList()))
        )
    var current: LeaveCheckpoint? = null
    var pending = emptyList<LeaveReplicaEntry>()
    var rejectWrite = false
    var failure: AppResult.Failed? = null
    var failNextObservation = false
    var readGate: CompletableDeferred<Unit>? = null
    var beforeCommit: suspend () -> Unit = {}
    var writes = 0
    var clears = 0
    var directoryObservers = 0
    var pageObservers = 0
    val selections = mutableListOf<LeaveSelection>()
    val historyPages = mutableMapOf<Pair<String, Long>, LeaveHistoryPage>()
    val historyWork = mutableMapOf<String, LeaveHistoryWork>()

    override fun observeDirectory(access: LeaveAccess): Flow<AppResult<LeaveReplicaDirectory>> =
        flow {
            directoryObservers++
            try {
                readGate?.await()
                if (failNextObservation) {
                    failNextObservation = false
                    emit(leaveFailure("client.storage", FailureKind.Storage))
                    return@flow
                }
                emitAll(
                    published.map { result ->
                        when (result) {
                            is AppResult.Failed -> result
                            is AppResult.Success ->
                                AppResult.Success(
                                    LeaveReplicaDirectory(
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
        access: LeaveAccess,
        selection: LeaveSelection,
    ): Flow<AppResult<LeaveReplicaPage>> = flow {
        selections += selection
        pageObservers++
        try {
            emitAll(
                published.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val records = result.value.entries.mapNotNull { it.record }
                            val balances =
                                records
                                    .filterIsInstance<LeaveReplicaRecord.Balance>()
                                    .filter {
                                        it.employeeId == selection.employeeId &&
                                            it.value.year == selection.year
                                    }
                                    .map { it.value }
                                    .sortedBy { it.accountId }
                                    .filter {
                                        selection.balanceAfter == null ||
                                            "b/" + it.accountId >
                                                requireNotNull(selection.balanceAfter)
                                    }
                                    .take(21)
                            val requests =
                                records
                                    .filterIsInstance<LeaveReplicaRecord.Request>()
                                    .map { it.value.fixtureSummary() }
                                    .filter {
                                        it.employeeId == selection.employeeId &&
                                            (selection.status == null ||
                                                it.status == selection.status)
                                    }
                                    .sortedBy(::requestOrder)
                                    .filter {
                                        selection.requestAfter == null ||
                                            requestOrder(it) >
                                                requireNotNull(selection.requestAfter)
                                    }
                                    .take(21)
                            AppResult.Success(
                                LeaveReplicaPage(
                                    result.value.checkpoint,
                                    LeavePage(
                                        balances.take(20),
                                        if (balances.size > 20) "b/" + balances[19].accountId
                                        else null,
                                    ),
                                    LeavePage(
                                        requests.take(20),
                                        if (requests.size > 20) requestOrder(requests[19]) else null,
                                    ),
                                )
                            )
                        }
                    }
                }
            )
        } finally {
            pageObservers--
        }
    }

    override fun observeRequest(
        access: LeaveAccess,
        selection: LeaveRequestSelection,
    ): Flow<AppResult<LeaveRequestSnapshot?>> =
        published.map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> {
                    val checkpoint = result.value.checkpoint
                    val entry =
                        result.value.entries.firstOrNull { it.reference.id == selection.requestId }
                    val request = (entry?.record as? LeaveReplicaRecord.Request)?.value
                    val history =
                        if (selection.historyAfter == null) request?.history
                        else historyPages[selection.requestId to selection.historyAfter]?.history
                    AppResult.Success(
                        if (checkpoint == null || request == null || history == null) null
                        else
                            LeaveRequestSnapshot(
                                checkpoint.access,
                                acquisition(checkpoint, request),
                                selection,
                                request.copy(history = history),
                                requireNotNull(entry.acquiredAt),
                                checkpoint.refreshAfter,
                            )
                    )
                }
            }
        }

    override suspend fun requestCheckpoint(access: LeaveAccess): AppResult<String?> =
        failure ?: AppResult.Success(current?.let { "${it.generation}:${it.revision}" })

    override suspend fun replaceRequest(
        access: LeaveAccess,
        requestId: String,
        expectedCheckpoint: String?,
        snapshot: LeaveRequestSnapshot?,
        history: LeaveHistoryPage?,
    ): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite || requestCheckpoint(access) != AppResult.Success(expectedCheckpoint))
            return AppResult.Success(false)
        if (snapshot == null) return clear(access)
        error(
            "Individual acquisition uses LeaveRequestFixture; this fixture drives feed publication"
        )
    }

    override suspend fun pendingHistory(
        access: LeaveAccess,
        limit: Int,
    ): AppResult<List<LeaveHistoryWork>> =
        failure ?: AppResult.Success(historyWork.values.take(limit))

    override suspend fun storeHistory(
        access: LeaveAccess,
        work: LeaveHistoryWork,
        page: LeaveHistoryPage,
    ): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite || historyWork[work.requestId] != work) return AppResult.Success(false)
        historyPages[work.requestId to work.after] = page
        val next = page.history.nextCursor?.toLong()
        if (next == null) historyWork.remove(work.requestId)
        else historyWork[work.requestId] = work.copy(after = next)
        current = current?.let { it.copy(revision = it.revision + 1) }
        val previous = (published.value as AppResult.Success).value
        published.value = AppResult.Success(previous.copy(checkpoint = current))
        return AppResult.Success(true)
    }

    override fun observePublishedCheckpoint(access: LeaveAccess) =
        published.map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> AppResult.Success(result.value.checkpoint)
            }
        }

    override suspend fun checkpoint(access: LeaveAccess): AppResult<LeaveCheckpoint?> =
        failure ?: AppResult.Success(current)

    override suspend fun begin(
        checkpoint: LeaveCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite || expectedGeneration != current?.generation)
            return AppResult.Success(false)
        current = checkpoint
        pending = emptyList()
        return AppResult.Success(true)
    }

    override suspend fun commit(
        previous: LeaveCheckpoint,
        next: LeaveCheckpoint,
        directory: LeaveDirectory,
        entries: List<LeaveReplicaEntry>,
    ): AppResult<Boolean> {
        beforeCommit()
        failure?.let {
            return it
        }
        if (rejectWrite || current != previous) return AppResult.Success(false)
        val merged =
            pending.associateBy { it.reference.collection to it.reference.id }.toMutableMap()
        for (entry in entries) {
            val id = entry.reference.collection to entry.reference.id
            val existing = merged[id]
            if (
                existing == null ||
                    entry.reference.version > existing.reference.version ||
                    (entry.reference.version == existing.reference.version && entry.record == null)
            )
                merged[id] = entry
        }
        pending = merged.values.toList()
        current = next
        writes++
        if (next.published) {
            val old = (published.value as? AppResult.Success)?.value?.checkpoint
            if (old?.generation != next.generation) {
                historyWork.clear()
                historyPages.clear()
            }
            for (entry in if (old?.generation == next.generation) entries else pending) {
                val request = (entry.record as? LeaveReplicaRecord.Request)?.value
                if (request == null) historyWork.remove(entry.reference.id)
                else {
                    val after = request.history.nextCursor?.toLong()
                    if (after == null) historyWork.remove(request.id)
                    else
                        historyWork[request.id] =
                            LeaveHistoryWork(
                                request.id,
                                request.version,
                                acquisition(next, request),
                                after,
                            )
                }
            }
            published.value = AppResult.Success(Published(next, directory, pending))
        }
        return AppResult.Success(true)
    }

    override suspend fun clear(access: LeaveAccess): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite) return AppResult.Success(false)
        current = null
        historyWork.clear()
        historyPages.clear()
        pending = emptyList()
        published.value = AppResult.Success(Published(null, null, emptyList()))
        clears++
        return AppResult.Success(true)
    }
}

private fun requestOrder(item: LeaveRequestSummary) =
    "r/" +
        (Long.MAX_VALUE - item.submittedAt.toEpochMilliseconds()).toString().padStart(19, '0') +
        "/" +
        item.id

private fun acquisition(checkpoint: LeaveCheckpoint, request: LeaveRequestDetails) =
    "${checkpoint.generation}:${request.id}:${request.version}"
