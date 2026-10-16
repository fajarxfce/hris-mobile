package dev.fajar.starter.people.domain.replica.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.replica.entities.*
import kotlinx.coroutines.flow.Flow

interface DirectoryReplicaRepository {
    fun observeAdmission(access: DirectoryAccess): Flow<AppResult<DirectoryReadAdmission>>

    suspend fun admission(access: DirectoryAccess): AppResult<DirectoryReadAdmission>

    suspend fun restrict(access: DirectoryAccess, failure: Failure): AppResult<Boolean>

    suspend fun admit(access: DirectoryAccess, expectedRevision: String?): AppResult<Boolean>

    fun observe(access: DirectoryAccess): Flow<AppResult<DirectorySnapshot>>

    /** Metadata from the visible generation only, without reading its documents. */
    fun observePublishedCheckpoint(access: DirectoryAccess): Flow<AppResult<DirectoryCheckpoint?>>

    suspend fun checkpoint(access: DirectoryAccess): AppResult<DirectoryCheckpoint?>

    suspend fun begin(
        checkpoint: DirectoryCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    suspend fun commit(
        previous: DirectoryCheckpoint,
        next: DirectoryCheckpoint,
        entries: List<DirectoryReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: DirectoryAccess): AppResult<Boolean>
}
