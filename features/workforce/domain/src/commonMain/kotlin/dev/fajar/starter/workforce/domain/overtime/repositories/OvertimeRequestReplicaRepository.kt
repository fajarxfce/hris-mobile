package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.coroutines.flow.Flow

interface OvertimeRequestReplicaRepository {
    fun observeRequest(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
    ): Flow<AppResult<OvertimeSnapshot<OvertimeRequestView>?>>

    suspend fun requestCheckpoint(access: WorkforceAccess): AppResult<String?>

    suspend fun replaceRequest(
        access: WorkforceAccess,
        requestId: String,
        expectedCheckpoint: String?,
        snapshot: OvertimeSnapshot<OvertimeRequestView>?,
        history: OvertimeHistoryPage? = null,
    ): AppResult<Boolean>

    suspend fun pendingHistory(
        access: WorkforceAccess,
        limit: Int = 8,
    ): AppResult<List<OvertimeHistoryWork>>

    suspend fun storeHistory(
        access: WorkforceAccess,
        work: OvertimeHistoryWork,
        page: OvertimeHistoryPage,
    ): AppResult<Boolean>
}
