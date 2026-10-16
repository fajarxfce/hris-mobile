package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.coroutines.flow.Flow

/** Current request facts and acquired activity pages, retained independently for every ID. */
interface LeaveRequestReplicaRepository {
    fun observeAdmission(access: LeaveAccess): Flow<AppResult<LeaveReadAdmission>>

    suspend fun admission(access: LeaveAccess): AppResult<LeaveReadAdmission>

    suspend fun restrict(access: LeaveAccess, failure: Failure): AppResult<Boolean>

    /** Only acquisition started after the latest denial may reopen local reads. */
    suspend fun admit(access: LeaveAccess, expectedRevision: String?): AppResult<Boolean>

    fun observeRequest(
        access: LeaveAccess,
        selection: LeaveRequestSelection,
    ): Flow<AppResult<LeaveRequestSnapshot?>>

    /** Opaque compare-and-set checkpoint for an atomic update of request records. */
    suspend fun requestCheckpoint(access: LeaveAccess): AppResult<String?>

    suspend fun replaceRequest(
        access: LeaveAccess,
        requestId: String,
        expectedCheckpoint: String?,
        snapshot: LeaveRequestSnapshot?,
        history: LeaveHistoryPage? = null,
    ): AppResult<Boolean>

    suspend fun pendingHistory(
        access: LeaveAccess,
        limit: Int = 8,
    ): AppResult<List<LeaveHistoryWork>>

    suspend fun storeHistory(
        access: LeaveAccess,
        work: LeaveHistoryWork,
        page: LeaveHistoryPage,
    ): AppResult<Boolean>
}
