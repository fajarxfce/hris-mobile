package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*

/** Canonical reads used by synchronization, independent of directory filters. */
interface LeaveRecordRepository {
    suspend fun request(access: LeaveAccess, id: String): AppResult<LeaveReplicaRecord.Request>

    suspend fun balance(access: LeaveAccess, id: String): AppResult<LeaveReplicaRecord.Balance>
}
