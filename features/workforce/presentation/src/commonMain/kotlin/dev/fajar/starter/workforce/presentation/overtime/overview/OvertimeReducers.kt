package dev.fajar.starter.workforce.presentation.overtime.overview

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache

internal fun OvertimeState.withCachedOvertime(
    result: AppResult<OvertimeSnapshot<OvertimeOverview>?>
): OvertimeState =
    when (result) {
        is AppResult.Failed ->
            copy(
                content = null,
                dialog = null,
                pickerYear = null,
                loading = false,
                cacheFailure = result.failure,
            )
        is AppResult.Success -> {
            val snapshot = result.value
            // A replacement window cancels the former lease timer. Never retain its old content.
            val matches =
                snapshot == null || selection == null || selection.matches(snapshot.value.selection)
            copy(
                content = snapshot?.takeIf { matches },
                loading = false,
                cacheFailure = null,
                selection = if (matches && snapshot != null) snapshot.value.selection else selection,
            )
        }
    }

internal fun OvertimeState.withOvertimeRefresh(
    result: AppResult<Unit>,
    requested: OvertimeSelection,
): OvertimeState =
    when (result) {
        is AppResult.Failed -> {
            val invalidated = overtimeFailureInvalidatesCache(result.failure)
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = if (invalidated) null else content,
                dialog = if (invalidated) null else dialog,
                pickerYear = if (invalidated) null else pickerYear,
                selection = selection ?: content?.value?.selection?.takeUnless { invalidated },
            )
        }
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
                selection = requested,
            )
    }

private fun OvertimeSelection.matches(other: OvertimeSelection): Boolean =
    (employeeId == null || employeeId == other.employeeId) &&
        (period == null || period == other.period) &&
        status == other.status &&
        employmentAfter == other.employmentAfter &&
        requestAfter == other.requestAfter
