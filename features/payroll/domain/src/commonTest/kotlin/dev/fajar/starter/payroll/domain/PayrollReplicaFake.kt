package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.replica.entities.*
import dev.fajar.starter.payroll.domain.replica.repositories.PayslipReplicaRepository
import dev.fajar.starter.sync.domain.entities.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.datetime.YearMonth

data class PayrollTestGeneration(
    val checkpoint: PayrollCheckpoint?,
    val entries: List<PayrollReplicaEntry>,
)

class PayrollReplicaFake(
    private val owns: (PayrollAccess) -> Boolean,
    private val clearLegacy: () -> Unit,
) : PayslipReplicaRepository {
    var current: PayrollCheckpoint? = null
    private val staged = linkedMapOf<Pair<SyncCollection, String>, PayrollReplicaEntry>()
    val published =
        MutableStateFlow<AppResult<PayrollTestGeneration>>(
            AppResult.Success(PayrollTestGeneration(null, emptyList()))
        )
    val readAdmission = MutableStateFlow(PayrollReadAdmission(null, null))
    var beforeWrite: suspend (Boolean) -> Unit = {}
    var beforeAdmit: suspend () -> Unit = {}
    var beforeAdmissionRead: suspend () -> Unit = {}
    var failCleanup = false
    var failAdmissionWrite = false
    var failNextObservation = false
    var failNextDetailObservation = false
    var observers = 0
    var admissionObservers = 0
    var writes = 0
    var clears = 0
    var begins = 0
    private var denialRevision = 0

    override fun observeAdmission(access: PayrollAccess) = flow {
        admissionObservers++
        try {
            emitAll(readAdmission.map { AppResult.Success(it) })
        } finally {
            admissionObservers--
        }
    }

    override suspend fun admission(access: PayrollAccess): AppResult<PayrollReadAdmission> {
        beforeAdmissionRead()
        return AppResult.Success(readAdmission.value)
    }

    override suspend fun restrict(access: PayrollAccess, failure: Failure): AppResult<Boolean> {
        if (!owns(access)) return AppResult.Success(false)
        readAdmission.value = PayrollReadAdmission("denial-${++denialRevision}", failure)
        return if (failAdmissionWrite) payslipFailure("fixture.storage", FailureKind.Storage)
        else AppResult.Success(true)
    }

    override suspend fun admit(
        access: PayrollAccess,
        expectedRevision: String?,
    ): AppResult<Boolean> {
        beforeAdmit()
        currentCoroutineContext().ensureActive()
        if (!owns(access) || readAdmission.value.revision != expectedRevision)
            return AppResult.Success(false)
        if (failAdmissionWrite) return payslipFailure("fixture.storage", FailureKind.Storage)
        readAdmission.value = PayrollReadAdmission(null, null)
        return AppResult.Success(true)
    }

    override fun observe(
        access: PayrollAccess,
        selection: PayslipSelection,
    ): Flow<AppResult<PayrollReplicaOverview>> = flow {
        if (failNextObservation) {
            failNextObservation = false
            emit(payslipFailure("fixture.storage", FailureKind.Storage))
            return@flow
        }
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
                                        (it.record as? PayrollRecord.Slip)?.value?.summary
                                    }
                                    .filter { it.month.year == selection.year }
                                    .sortedWith(
                                        compareByDescending<PayslipSummary> { it.month }
                                            .thenBy { it.id }
                                    )
                                    .filter { row ->
                                        selection.after?.let { after ->
                                            row.month < YearMonth.parse(after.take(7)) ||
                                                (row.month == YearMonth.parse(after.take(7)) &&
                                                    row.id > after.drop(8))
                                        } ?: true
                                    }
                            val items = all.take(20)
                            AppResult.Success(
                                PayrollReplicaOverview(
                                    result.value.checkpoint,
                                    PayslipPage(
                                        items,
                                        if (all.size > 20)
                                            "${items.last().month}:${items.last().id}"
                                        else null,
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
        access: PayrollAccess,
        id: String,
    ): Flow<AppResult<PayrollReplicaDetails>> = flow {
        if (failNextDetailObservation) {
            failNextDetailObservation = false
            emit(payslipFailure("fixture.storage", FailureKind.Storage))
            return@flow
        }
        observers++
        try {
            emitAll(
                published.map { result ->
                    when (result) {
                        is AppResult.Failed -> result
                        is AppResult.Success -> {
                            val records =
                                result.value.entries.mapNotNull { it.record }.filter { it.id == id }
                            val slip =
                                records.filterIsInstance<PayrollRecord.Slip>().singleOrNull()?.value
                            val payments =
                                records
                                    .filterIsInstance<PayrollRecord.Payments>()
                                    .singleOrNull()
                                    ?.value
                            AppResult.Success(
                                PayrollReplicaDetails(
                                    result.value.checkpoint,
                                    slip?.let { PayslipView(it, requireNotNull(payments)) },
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

    override suspend fun checkpoint(access: PayrollAccess) = AppResult.Success(current)

    override suspend fun begin(
        checkpoint: PayrollCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean> {
        if (!owns(checkpoint.access) || current?.generation != expectedGeneration)
            return AppResult.Success(false)
        begins++
        current = checkpoint
        staged.clear()
        return AppResult.Success(true)
    }

    override suspend fun commit(
        previous: PayrollCheckpoint,
        next: PayrollCheckpoint,
        entries: List<PayrollReplicaEntry>,
    ): AppResult<Boolean> {
        beforeWrite(false)
        currentCoroutineContext().ensureActive()
        if (!owns(next.access) || current != previous) return AppResult.Success(false)
        for (entry in entries) {
            val id = entry.reference.collection to entry.reference.id
            val old = staged[id]
            if (
                old == null ||
                    entry.reference.version > old.reference.version ||
                    (entry.reference.version == old.reference.version &&
                        (old.record != null || entry.record == null))
            )
                staged[id] = entry
        }
        current = next
        writes++
        if (next.published)
            published.value = AppResult.Success(PayrollTestGeneration(next, staged.values.toList()))
        return AppResult.Success(true)
    }

    override suspend fun clear(access: PayrollAccess): AppResult<Boolean> {
        beforeWrite(true)
        currentCoroutineContext().ensureActive()
        if (!owns(access)) return AppResult.Success(false)
        if (failCleanup) return payslipFailure("fixture.storage", FailureKind.Storage)
        clears++
        clearLegacy()
        current = null
        staged.clear()
        published.value = AppResult.Success(PayrollTestGeneration(null, emptyList()))
        return AppResult.Success(true)
    }
}
