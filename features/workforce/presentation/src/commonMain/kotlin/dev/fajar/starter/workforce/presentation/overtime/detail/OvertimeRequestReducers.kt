package dev.fajar.starter.workforce.presentation.overtime.detail

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.overtimeFailureInvalidatesCache

internal fun OvertimeRequestState.withCachedRequest(
    result: AppResult<OvertimeSnapshot<OvertimeRequestView>?>
): OvertimeRequestState =
    when (result) {
        is AppResult.Failed -> copy(content = null, loading = false, cacheFailure = result.failure)
        is AppResult.Success ->
            copy(
                content = result.value?.takeIf { it.value.selection == selection },
                loading = false,
                cacheFailure = null,
            )
    }

internal fun OvertimeRequestState.withRequestRefresh(
    result: AppResult<Unit>
): OvertimeRequestState =
    when (result) {
        is AppResult.Failed ->
            copy(
                refreshing = false,
                loading = false,
                refreshFailure = result.failure,
                content = content?.takeUnless { overtimeFailureInvalidatesCache(result.failure) },
            )
        is AppResult.Success ->
            copy(
                refreshing = false,
                loading = content == null,
                cacheFailure = null,
                refreshFailure = null,
            )
    }
