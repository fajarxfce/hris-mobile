package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

/** Explicit selection prevents a queued page action from acquiring a replacement company. */
interface SyncCompanyData : SyncTask {
    val dataSet: CompanyDataSet

    suspend fun refresh(workspaceId: String): AppResult<CompanyDataSyncOutcome>
}
