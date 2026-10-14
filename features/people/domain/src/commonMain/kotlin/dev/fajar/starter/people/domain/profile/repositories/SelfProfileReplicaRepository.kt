package dev.fajar.starter.people.domain.profile.repositories

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.entities.*
import kotlinx.coroutines.flow.Flow

interface SelfProfileReplicaRepository {
    fun observeAdmission(access: SelfProfileAccess): Flow<AppResult<SelfProfileReadAdmission>>

    suspend fun admission(access: SelfProfileAccess): AppResult<SelfProfileReadAdmission>

    suspend fun restrict(access: SelfProfileAccess, failure: Failure): AppResult<Boolean>

    suspend fun admit(access: SelfProfileAccess, expectedRevision: String?): AppResult<Boolean>

    fun observe(access: SelfProfileAccess): Flow<AppResult<SelfProfileSnapshot>>

    suspend fun checkpoint(access: SelfProfileAccess): AppResult<SelfProfileCheckpoint?>

    suspend fun begin(
        checkpoint: SelfProfileCheckpoint,
        expectedGeneration: String?,
    ): AppResult<Boolean>

    suspend fun commit(
        previous: SelfProfileCheckpoint,
        next: SelfProfileCheckpoint,
        entries: List<SelfProfileReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: SelfProfileAccess): AppResult<Boolean>
}
