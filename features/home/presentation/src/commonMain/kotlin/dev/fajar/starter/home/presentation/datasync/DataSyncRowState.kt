package dev.fajar.starter.home.presentation.datasync

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus

data class DataSyncRowState(
    val dataSet: CompanyDataSet,
    val status: CompanyDataStatus? = null,
    val loading: Boolean = true,
    val readFailure: Failure? = null,
    val syncFailure: Failure? = null,
    val progress: DataSyncProgress = DataSyncProgress.Idle,
) {
    val syncing: Boolean
        get() = progress == DataSyncProgress.Queued || progress == DataSyncProgress.Running

    val failure: Failure?
        get() = readFailure ?: syncFailure
}
