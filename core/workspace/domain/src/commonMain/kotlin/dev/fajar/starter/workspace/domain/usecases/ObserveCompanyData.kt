package dev.fajar.starter.workspace.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import kotlinx.coroutines.flow.Flow

/** Implemented by feature-owned observation use cases, including their normal read admission. */
interface ObserveCompanyData {
    val dataSet: CompanyDataSet

    fun observeStatus(workspaceId: String): Flow<AppResult<CompanyDataStatus>>
}
