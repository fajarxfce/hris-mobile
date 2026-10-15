package dev.fajar.starter.payroll.domain.policies

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.payroll.domain.entities.Payslip

/** A historical statement must belong to the currently admitted employee and payroll period. */
fun validatePayslipRevision(
    current: Payslip,
    revision: Long,
    historical: Payslip,
): AppResult<Unit> =
    if (
        revision < 0 ||
            revision > current.summary.version ||
            historical.summary.version != revision ||
            historical.summary.id != current.summary.id ||
            historical.summary.employeeId != current.summary.employeeId ||
            historical.summary.month != current.summary.month ||
            historical.summary.currency != current.summary.currency ||
            (revision == current.summary.version && historical != current)
    )
        payslipFailure("client.invalid_response", FailureKind.Unexpected)
    else AppResult.Success(Unit)
