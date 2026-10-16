package dev.fajar.starter.home.presentation.datasync

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

fun DataSyncRowState.withDataStatus(result: AppResult<CompanyDataStatus>): DataSyncRowState =
    when (result) {
        is AppResult.Failed -> copy(status = null, loading = false, readFailure = result.failure)
        is AppResult.Success -> copy(status = result.value, loading = false, readFailure = null)
    }

fun DataSyncRowState.withDataSyncResult(
    result: AppResult<CompanyDataSyncOutcome>
): DataSyncRowState =
    when (result) {
        is AppResult.Failed -> copy(progress = DataSyncProgress.Idle, syncFailure = result.failure)
        is AppResult.Success ->
            copy(
                progress =
                    if (result.value.caughtUp) DataSyncProgress.Idle
                    else DataSyncProgress.Incomplete,
                syncFailure = null,
            )
    }
