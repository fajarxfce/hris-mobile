package dev.fajar.starter.payroll.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache

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

internal fun PayslipState.withPayslipRefresh(result: AppResult<Unit>): PayslipState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = content?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
                exportOptions = false,
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
                exportFailure = null,
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
