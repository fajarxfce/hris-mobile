package dev.fajar.starter.payroll.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailure
import dev.fajar.starter.payroll.domain.repositories.PayslipRevisionRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*

class PayrollRevisionFake(private val owns: (PayrollAccess) -> Boolean) :
    PayslipRevisionRepository {
    val records = MutableStateFlow<Map<List<String>, Payslip>>(emptyMap())
    var observers = 0
    var writes = 0
    var beforeWrite: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var writeResult: AppResult<Boolean>? = null
    var failNextObservation = false

    override fun observeRevision(
        access: PayrollAccess,
        id: String,
        revision: Long,
    ): Flow<AppResult<Payslip?>> = flow {
        beforeRead()
        if (failNextObservation) {
            failNextObservation = false
            emit(payslipFailure("fixture.storage", FailureKind.Storage))
            return@flow
        }
        observers++
        try {
            emitAll(
                records.map { all ->
                    if (!owns(access))
                        payslipFailure("client.payslip_scope_changed", FailureKind.AccessDenied)
                    else AppResult.Success(all[key(access, id, revision)])
                }
            )
        } finally {
            observers--
        }
    }

    override suspend fun retainRevision(
        access: PayrollAccess,
        payslip: Payslip,
    ): AppResult<Boolean> {
        beforeWrite()
        currentCoroutineContext().ensureActive()
        if (!owns(access)) return AppResult.Success(false)
        writeResult?.let {
            return it
        }
        val key = key(access, payslip.summary.id, payslip.summary.version)
        val previous = records.value[key]
        if (previous != null && previous != payslip)
            return payslipFailure("fixture.immutable_revision", FailureKind.Storage)
        if (previous == null) {
            records.value += key to payslip
            writes++
        }
        return AppResult.Success(true)
    }

    fun clear(access: PayrollAccess) {
        records.value =
            records.value.filterKeys {
                it.take(3) != listOf(access.audience, access.accountId, access.companyId)
            }
    }

    private fun key(access: PayrollAccess, id: String, revision: Long) =
        listOf(access.audience, access.accountId, access.companyId, id, revision.toString())
}
