package dev.fajar.starter.workforce.domain.overtime.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.coroutines.flow.Flow

interface OvertimeRequestReplicaRepository {
    fun observeAdmission(access: WorkforceAccess): Flow<AppResult<OvertimeReadAdmission>>

    suspend fun admission(access: WorkforceAccess): AppResult<OvertimeReadAdmission>

    suspend fun restrict(access: WorkforceAccess, failure: Failure): AppResult<Boolean>

    /** Only acquisition started after the latest denial may reopen local reads. */
    suspend fun admit(access: WorkforceAccess, expectedRevision: String?): AppResult<Boolean>

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
