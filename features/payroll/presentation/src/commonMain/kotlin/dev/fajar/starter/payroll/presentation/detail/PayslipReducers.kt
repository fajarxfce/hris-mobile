package dev.fajar.starter.payroll.presentation.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache
import dev.fajar.starter.payroll.domain.replica.entities.PayrollSyncOutcome

internal fun PayslipState.withCachedPayslip(result: AppResult<PayslipRevisionRead?>): PayslipState =
    when (result) {
        is AppResult.Failed ->
            copy(
                content = null,
                currentRevision =
                    currentRevision?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
                loading = false,
                cacheFailure = result.failure,
                exportRevision = null,
            )
        is AppResult.Success ->
            copy(
                content = result.value?.snapshot,
                currentRevision = result.value?.currentRevision,
                loading = result.value?.snapshot == null && revisionLoading,
                cacheFailure = null,
                exportRevision = exportRevision.takeIf { result.value?.snapshot != null },
            )
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
                currentRevision =
                    currentRevision?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
                exportRevision = null,
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
                revisionFailure =
                    if (result.value.caughtUp) null
                    else revisionFailure?.takeIf(::payslipFailureInvalidatesCache),
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
                currentRevision =
                    currentRevision?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success -> copy(saving = false, saved = result.value, exportFailure = null)
    }

internal fun PayslipState.withRevisionLoad(result: AppResult<Unit>): PayslipState =
    when (result) {
        is AppResult.Failed ->
            copy(
                revisionLoading = false,
                loading = false,
                revisionFailure = result.failure,
                content = content?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
                currentRevision =
                    currentRevision?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
                exportRevision = null,
            )
        is AppResult.Success ->
            copy(
                revisionLoading = false,
                loading = false,
                revisionFailure = revisionFailure?.takeIf(::payslipFailureInvalidatesCache),
            )
    }
