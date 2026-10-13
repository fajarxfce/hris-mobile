package dev.fajar.starter.payroll.presentation.overview

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.payslipFailureInvalidatesCache

internal fun PayslipsState.withCachedPayslips(
    result: AppResult<PayslipSnapshot<PayslipOverview>?>
): PayslipsState =
    when (result) {
        is AppResult.Failed -> copy(content = null, loading = false, cacheFailure = result.failure)
        is AppResult.Success -> {
            val snapshot = result.value
            val matches =
                snapshot == null ||
                    selection == null ||
                    ((selection.year == null || selection.year == snapshot.value.selection.year) &&
                        selection.after == snapshot.value.selection.after)
            copy(
                content = snapshot?.takeIf { matches },
                loading = false,
                cacheFailure = null,
                selection = if (matches && snapshot != null) snapshot.value.selection else selection,
            )
        }
    }

internal fun PayslipsState.withPayslipsRefresh(
    result: AppResult<Unit>,
    requested: PayslipSelection,
): PayslipsState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = content?.takeUnless { payslipFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
                selection = requested,
            )
    }
