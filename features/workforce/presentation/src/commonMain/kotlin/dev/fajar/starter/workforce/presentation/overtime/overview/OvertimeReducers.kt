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
            copy(
                content = snapshot,
                loading = false,
                cacheFailure = null,
                selection = snapshot?.value?.selection ?: selection,
            )
        }
    }

internal fun OvertimeState.withOvertimeRefresh(
    result: AppResult<OvertimeSyncOutcome>
): OvertimeState =
    when (result) {
        is AppResult.Failed -> {
            val invalidated = overtimeFailureInvalidatesCache(result.failure)
            copy(
                refreshing = false,
                syncPending = false,
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
                loading = false,
                syncPending = !result.value.caughtUp,
                cacheFailure = if (result.value.caughtUp) null else cacheFailure,
                refreshFailure = if (result.value.caughtUp) null else refreshFailure,
            )
    }
