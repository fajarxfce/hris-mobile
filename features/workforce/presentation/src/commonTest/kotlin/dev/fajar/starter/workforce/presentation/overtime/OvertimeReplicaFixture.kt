package dev.fajar.starter.workforce.presentation.overtime

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeReplicaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

/** Application-port probe; physical transaction/encryption behavior is covered in data tests. */
class OvertimeReplicaFixture : OvertimeReplicaRepository {
    data class Published(
        val checkpoint: OvertimeCheckpoint?,
        val directory: WorkforceDirectory?,
        val entries: List<OvertimeReplicaEntry>,
    )

    val published =
        MutableStateFlow<AppResult<Published>>(
            AppResult.Success(Published(null, null, emptyList()))
        )
    var current: OvertimeCheckpoint? = null
    var pending = emptyList<OvertimeReplicaEntry>()
    var failure: AppResult.Failed? = null
    var rejectWrite = false
    var failCleanup = false
    var beforeCommit: suspend () -> Unit = {}
    var failNextObservation = false
    var readGate: CompletableDeferred<Unit>? = null
    var writes = 0
    var clears = 0
    var directoryObservers = 0
    var pageObservers = 0
    val selections = mutableListOf<OvertimeSelection>()
    val historyPages = mutableMapOf<Pair<String, Long>, OvertimeHistoryPage>()
    val historyWork = mutableMapOf<String, OvertimeHistoryWork>()

    var admissionObservers = 0
    var failAdmissionWrite = false
    var rejectAdmission = false
    var denials = 0
    var admissionWrites = 0
    var beforeAdmissionWrite: suspend () -> Unit = {}
    val readAdmission = MutableStateFlow(OvertimeReadAdmission(null, null))

    override fun observeAdmission(access: WorkforceAccess) = flow {
        admissionObservers++
        try {
            emitAll(readAdmission.map { AppResult.Success(it) })
        } finally {
            admissionObservers--
        }
    }

    override suspend fun admission(access: WorkforceAccess) = AppResult.Success(readAdmission.value)

    override suspend fun restrict(access: WorkforceAccess, failure: Failure): AppResult<Boolean> {
        readAdmission.value = OvertimeReadAdmission("read-denial-${++denials}", failure)
        beforeAdmissionWrite()
        admissionWrites++
        return if (failAdmissionWrite) overtimeFailure("fixture.storage", FailureKind.Storage)
        else AppResult.Success(true)
    }

    override suspend fun admit(
        access: WorkforceAccess,
        expectedRevision: String?,
    ): AppResult<Boolean> {
        if (rejectAdmission || readAdmission.value.revision != expectedRevision)
            return AppResult.Success(false)
        readAdmission.value = OvertimeReadAdmission(null, null)
        return AppResult.Success(true)
    }

    override fun observeDirectory(
        access: WorkforceAccess
    ): Flow<AppResult<OvertimeReplicaDirectory>> = flow {
        directoryObservers++
        try {
            readGate?.await()
            if (failNextObservation) {
                failNextObservation = false
                emit(overtimeFailure("client.storage", FailureKind.Storage))
                return@flow
            }
            emitAll(
                published.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success ->
                            AppResult.Success(
                                OvertimeReplicaDirectory(
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
        access: WorkforceAccess,
        selection: OvertimeSelection,
    ): Flow<AppResult<OvertimeReplicaPage>> = flow {
        selections += selection
        pageObservers++
        try {
            emitAll(
                published.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val rows =
                                result.value.entries
                                    .mapNotNull { it.record?.request?.toSummary() }
                                    .filter {
                                        it.employeeId == selection.employeeId &&
                                            (selection.status == null ||
                                                it.status == selection.status) &&
                                            it.workDate in
                                                requireNotNull(selection.period).from..selection
                                                        .period!!
                                                        .until
                                    }
                                    .sortedBy(::requestOrder)
                                    .filter {
                                        selection.requestAfter == null ||
                                            requestOrder(it) > selection.requestAfter!!
                                    }
                                    .take(21)
                            AppResult.Success(
                                OvertimeReplicaPage(
                                    result.value.checkpoint,
                                    OvertimePage(
                                        rows.take(20),
                                        if (rows.size > 20) requestOrder(rows[19]) else null,
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
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>> =
        published.map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> {
                    val checkpoint = result.value.checkpoint
                    val entry =
                        result.value.entries.firstOrNull { it.reference.id == selection.requestId }
                    val details = entry?.record
                    val history =
                        if (selection.historyAfter == null) details?.history
                        else historyPages[selection.requestId to selection.historyAfter!!]?.history
                    AppResult.Success(
                        if (checkpoint == null || details == null || history == null) null
                        else
                            OvertimeSnapshot(
                                checkpoint.access,
                                acquisition(checkpoint, details.request),
                                OvertimeRequestView(selection, details.copy(history = history)),
                                requireNotNull(entry.acquiredAt),
                                requireNotNull(entry.acquiredAt) + OVERTIME_FRESHNESS_PERIOD,
                            )
                    )
                }
            }
        }

    override suspend fun checkpoint(access: WorkforceAccess): AppResult<OvertimeCheckpoint?> =
        failure ?: AppResult.Success(current)

    override suspend fun requestCheckpoint(access: WorkforceAccess): AppResult<String?> =
        failure ?: AppResult.Success(current?.let { "${it.generation}:${it.revision}" })

    override suspend fun replaceRequest(
        access: WorkforceAccess,
        requestId: String,
        expectedCheckpoint: String?,
        snapshot: OvertimeSnapshot<OvertimeRequestView>?,
        history: OvertimeHistoryPage?,
    ): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite || requestCheckpoint(access) != AppResult.Success(expectedCheckpoint))
            return AppResult.Success(false)
        if (snapshot == null) return clear(access)
        error("Individual request acquisition uses the narrow request fixture")
    }

    override suspend fun begin(
        checkpoint: OvertimeCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite || current?.generation != expectedGeneration)
            return AppResult.Success(false)
        current = checkpoint
        pending = emptyList()
        return AppResult.Success(true)
    }

    override suspend fun commit(
        previous: OvertimeCheckpoint,
        next: OvertimeCheckpoint,
        directory: WorkforceDirectory,
        entries: List<OvertimeReplicaEntry>,
    ): AppResult<Boolean> {
        beforeCommit()
        failure?.let {
            return it
        }
        if (rejectWrite || current != previous) return AppResult.Success(false)
        val merged = pending.associateBy { it.reference.id }.toMutableMap()
        for (entry in entries) {
            val old = merged[entry.reference.id]
            if (old == null || entry.reference.version >= old.reference.version)
                merged[entry.reference.id] = entry
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
                val request = entry.record?.request
                val after = entry.record?.history?.nextCursor?.toLong()
                if (request == null || after == null) historyWork.remove(entry.reference.id)
                else
                    historyWork[request.id] =
                        OvertimeHistoryWork(
                            request.id,
                            request.employeeId,
                            request.version,
                            acquisition(next, request),
                            after,
                        )
            }
            published.value = AppResult.Success(Published(next, directory, pending))
        }
        return AppResult.Success(true)
    }

    override suspend fun pendingHistory(
        access: WorkforceAccess,
        limit: Int,
    ): AppResult<List<OvertimeHistoryWork>> =
        failure ?: AppResult.Success(historyWork.values.take(limit))

    override suspend fun storeHistory(
        access: WorkforceAccess,
        work: OvertimeHistoryWork,
        page: OvertimeHistoryPage,
    ): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite || historyWork[work.requestId] != work) return AppResult.Success(false)
        historyPages[work.requestId to work.after] = page
        val next = page.history.nextCursor?.toLong()
        if (next == null) historyWork.remove(work.requestId)
        else historyWork[work.requestId] = work.copy(after = next)
        current = current?.copy(revision = current!!.revision + 1)
        val previous = (published.value as AppResult.Success).value
        published.value = AppResult.Success(previous.copy(checkpoint = current))
        return AppResult.Success(true)
    }

    override suspend fun clear(access: WorkforceAccess): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (failCleanup) return overtimeFailure("fixture.storage", FailureKind.Storage)
        if (rejectWrite) return AppResult.Success(false)
        current = null
        pending = emptyList()
        historyWork.clear()
        historyPages.clear()
        published.value = AppResult.Success(Published(null, null, emptyList()))
        clears++
        return AppResult.Success(true)
    }
}

private fun requestOrder(item: OvertimeSummary) = "r/" + item.workDate + "/" + item.id

private fun acquisition(checkpoint: OvertimeCheckpoint, request: OvertimeRequest) =
    "${checkpoint.generation}:${request.id}:${request.version}"
