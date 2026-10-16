package dev.fajar.starter.leave.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.leave.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface LeaveReplicaRepository : LeaveRequestReplicaRepository {
    fun observeDirectory(access: LeaveAccess): Flow<AppResult<LeaveReplicaDirectory>>

    fun observe(access: LeaveAccess, selection: LeaveSelection): Flow<AppResult<LeaveReplicaPage>>

    /** Metadata from the visible generation only, without reading its documents. */
    fun observePublishedCheckpoint(access: LeaveAccess): Flow<AppResult<LeaveCheckpoint?>>

    suspend fun checkpoint(access: LeaveAccess): AppResult<LeaveCheckpoint?>

    suspend fun begin(checkpoint: LeaveCheckpoint, expectedGeneration: String?): AppResult<Boolean>

    /** Records, directory metadata and progress commit together or remain unchanged. */
    suspend fun commit(
        previous: LeaveCheckpoint,
        next: LeaveCheckpoint,
        directory: LeaveDirectory,
        entries: List<LeaveReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: LeaveAccess): AppResult<Boolean>
}
