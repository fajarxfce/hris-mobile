package dev.fajar.starter.payroll.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*

/** All remote reads explicitly request the authenticated account's own employments. */
interface PayslipRepository {
    suspend fun list(access: PayrollAccess, selection: PayslipSelection): AppResult<PayslipPage>

    suspend fun details(access: PayrollAccess, id: String): AppResult<Payslip>

    suspend fun payments(access: PayrollAccess, id: String): AppResult<PayslipPayments>
}
