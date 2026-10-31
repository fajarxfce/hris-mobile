package dev.fajar.starter.fixtures

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.calendarFailure
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.policies.workforceRecordEmployee
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceReplicaRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*

/** Controlled replica I/O for UI admission, freshness, and delayed-notification scenarios. */
class NativeWorkforceReplicaUi : WorkforceReplicaRepository {
    data class Published(
        val checkpoint: WorkforceCheckpoint?,
        val directory: WorkforceDirectory?,
        val entries: List<WorkforceReplicaEntry>,
    )

    val published =
        MutableStateFlow<AppResult<Published>>(
            AppResult.Success(Published(null, null, emptyList()))
        )
    var current: WorkforceCheckpoint? = null
    var pending = emptyList<WorkforceReplicaEntry>()
    var failure: AppResult.Failed? = null
    var rejectWrite = false
    @Volatile var rejectClear = false
    @Volatile var publicationGate: CompletableDeferred<Unit>? = null
    var beforeCommit: suspend () -> Unit = {}
    var failNextObservation = false
    var readGate: CompletableDeferred<Unit>? = null
    var factsGate: CompletableDeferred<Unit>? = null
    var writes = 0
    var clears = 0
    var directoryObservers = 0
    private val factsObservers = AtomicInteger()
    val selections = mutableListOf<Pair<String, CalendarPeriod>>()

    val readAdmission = MutableStateFlow(WorkforceReadAdmission(null, null))
    var failAdmissionWrite = false
    private var denialSequence = 0

    override fun observeAdmission(access: WorkforceAccess) =
        readAdmission.map { AppResult.Success(it) }

    override suspend fun admission(access: WorkforceAccess) = AppResult.Success(readAdmission.value)

    override suspend fun restrict(access: WorkforceAccess, failure: Failure): AppResult<Boolean> {
        readAdmission.value = WorkforceReadAdmission((++denialSequence).toString(), failure)
        return if (failAdmissionWrite) calendarFailure("client.storage", FailureKind.Storage)
        else AppResult.Success(true)
    }

    override suspend fun admit(
        access: WorkforceAccess,
        expectedRevision: String?,
    ): AppResult<Boolean> {
        val previous = readAdmission.value
        return AppResult.Success(
            previous.revision == expectedRevision &&
                readAdmission.compareAndSet(previous, WorkforceReadAdmission(null, null))
        )
    }

    override fun observeDirectory(
        access: WorkforceAccess
    ): Flow<AppResult<WorkforceReplicaDirectory>> = flow {
        directoryObservers++
        try {
            readGate?.await()
            if (failNextObservation) {
                failNextObservation = false
                emit(calendarFailure("client.storage", FailureKind.Storage))
            } else
                emitAll(
                    published.map { result ->
                        if ((result as? AppResult.Success)?.value?.checkpoint != null)
                            publicationGate?.await()
                        when (result) {
                            is AppResult.Failed -> result
                            is AppResult.Success ->
                                AppResult.Success(
                                    WorkforceReplicaDirectory(
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

    override fun observeFacts(
        access: WorkforceAccess,
        employeeId: String,
        period: CalendarPeriod,
    ): Flow<AppResult<WorkforceReplicaFacts>> = flow {
        selections += employeeId to period
        factsObservers.incrementAndGet()
        try {
            factsGate?.await()
            emitAll(
                published.map { result ->
                    if ((result as? AppResult.Success)?.value?.checkpoint != null)
                        publicationGate?.await()
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val records =
                                result.value.entries
                                    .mapNotNull { it.record }
                                    .filter {
                                        workforceRecordEmployee(it) == null ||
                                            workforceRecordEmployee(it) == employeeId
                                    }
                            val rules =
                                records
                                    .filterIsInstance<WorkforceRecord.Schedule>()
                                    .filter { it.effectiveFrom <= period.until }
                                    .sortedByDescending { it.effectiveFrom }
                            val baseline = rules.firstOrNull { it.effectiveFrom <= period.from }
                            AppResult.Success(
                                WorkforceReplicaFacts(
                                    result.value.checkpoint,
                                    records
                                        .filterIsInstance<WorkforceRecord.Attendance>()
                                        .filter { it.day.date in period.from..period.until }
                                        .sortedBy { it.day.date },
                                    rules.filter {
                                        it.effectiveFrom > period.from || it == baseline
                                    },
                                    records
                                        .filterIsInstance<WorkforceRecord.Roster>()
                                        .filter { it.workDate in period.from..period.until }
                                        .sortedBy { it.workDate },
                                    records
                                        .filterIsInstance<WorkforceRecord.Holiday>()
                                        .filter { it.workDate in period.from..period.until }
                                        .sortedBy { it.workDate },
                                )
                            )
                        }
                    }
                }
            )
        } finally {
            factsObservers.decrementAndGet()
        }
    }

    override fun observePublishedCheckpoint(access: WorkforceAccess) =
        published.map { result ->
            when (result) {
                is AppResult.Failed -> result
                is AppResult.Success -> AppResult.Success(result.value.checkpoint)
            }
        }

    override suspend fun checkpoint(access: WorkforceAccess): AppResult<WorkforceCheckpoint?> =
        failure ?: AppResult.Success(current)

    override suspend fun begin(
        checkpoint: WorkforceCheckpoint,
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
        previous: WorkforceCheckpoint,
        next: WorkforceCheckpoint,
        directory: WorkforceDirectory,
        entries: List<WorkforceReplicaEntry>,
    ): AppResult<Boolean> {
        beforeCommit()
        failure?.let {
            return it
        }
        if (rejectWrite || current != previous) return AppResult.Success(false)
        val merged =
            pending.associateBy { it.reference.collection to it.reference.id }.toMutableMap()
        entries.forEach { entry ->
            val key = entry.reference.collection to entry.reference.id
            val saved = merged[key]
            if (
                saved == null ||
                    entry.reference.version > saved.reference.version ||
                    (entry.reference.version == saved.reference.version && entry.record == null)
            )
                merged[key] = entry
        }
        pending = merged.values.toList()
        current = next
        writes++
        if (next.published)
            published.value =
                AppResult.Success(Published(next, directory, pending.filter { it.record != null }))
        return AppResult.Success(true)
    }

    override suspend fun clear(access: WorkforceAccess): AppResult<Boolean> {
        if (rejectClear) return calendarFailure("client.storage", FailureKind.Storage)
        clears++
        current = null
        pending = emptyList()
        published.value = AppResult.Success(Published(null, null, emptyList()))
        return AppResult.Success(true)
    }

    fun hasSnapshot() = (published.value as? AppResult.Success)?.value?.checkpoint != null

    fun subscribers() = factsObservers.get()

    fun markStale() {
        val old = (published.value as AppResult.Success).value
        val checkpoint = requireNotNull(old.checkpoint)
        val stale =
            checkpoint.copy(
                refreshedAt = requireNotNull(checkpoint.refreshedAt) - 2.hours,
                refreshAfter = checkpoint.refreshAfter - 2.hours,
            )
        if (current?.generation == checkpoint.generation) current = stale
        published.value = AppResult.Success(old.copy(checkpoint = stale))
    }
}
