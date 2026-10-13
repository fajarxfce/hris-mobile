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
                                    .map { it.value }
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
        if (next.published) published.value = AppResult.Success(Published(next, directory, pending))
        return AppResult.Success(true)
    }

    override suspend fun clear(access: LeaveAccess): AppResult<Boolean> {
        failure?.let {
            return it
        }
        if (rejectWrite) return AppResult.Success(false)
        current = null
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
