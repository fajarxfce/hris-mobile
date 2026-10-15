package dev.fajar.starter.payroll.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.PayrollAccess
import dev.fajar.starter.payroll.domain.entities.Payslip
import kotlinx.coroutines.flow.Flow

/** Immutable acquired statements, owned by account/company independently of sync generations. */
interface PayslipRevisionRepository {
    fun observeRevision(
        access: PayrollAccess,
        id: String,
        revision: Long,
    ): Flow<AppResult<Payslip?>>

    /**
     * An existing revision must have exactly the same facts; storage never replaces its content.
     */
    suspend fun retainRevision(access: PayrollAccess, payslip: Payslip): AppResult<Boolean>
}
