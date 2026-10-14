package dev.fajar.starter.payroll.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.domain.replica.entities.PayrollSyncOutcome

internal fun PayslipState.withCachedPayslip(
    result: AppResult<PayslipSnapshot<PayslipView>?>
): PayslipState =
    when (result) {
        is AppResult.Failed ->
            copy(
                content = null,
                loading = false,
                cacheFailure = result.failure,
                exportOptions = false,
            )
        is AppResult.Success -> copy(content = result.value, loading = false, cacheFailure = null)
    }

internal fun PayslipState.withPayslipRefresh(result: AppResult<PayrollSyncOutcome>): PayslipState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                syncPending = false,
                loading = false,
                refreshFailure = result.failure,
                content = content?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
                exportOptions = false,
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = false,
                syncPending = !result.value.caughtUp,
                cacheFailure =
                    if (result.value.caughtUp) null
                    else cacheFailure?.takeIf(::payslipFailureInvalidatesCache),
                refreshFailure =
                    if (result.value.caughtUp) null
                    else refreshFailure?.takeIf(::payslipFailureInvalidatesCache),
                exportFailure =
                    if (result.value.caughtUp) null
                    else exportFailure?.takeIf(::payslipFailureInvalidatesCache),
            )
    }

internal fun PayslipState.withPayslipExport(result: AppResult<Boolean>): PayslipState =
    when (result) {
        is AppResult.Failed ->
            copy(
                saving = false,
                saved = false,
                exportFailure = result.failure,
                content = content?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success -> copy(saving = false, saved = result.value, exportFailure = null)
    }
