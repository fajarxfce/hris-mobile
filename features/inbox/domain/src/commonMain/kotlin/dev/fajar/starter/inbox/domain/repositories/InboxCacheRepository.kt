package dev.fajar.starter.inbox.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.inbox.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface InboxCacheRepository {
    fun observeAdmission(access: InboxAccess): Flow<AppResult<InboxReadAdmission>>

    suspend fun admission(access: InboxAccess): AppResult<InboxReadAdmission>

    suspend fun restrict(access: InboxAccess, failure: Failure): AppResult<Boolean>

    /** Only acquisition started after this restriction may reopen the shared projection. */
    suspend fun admit(access: InboxAccess, expectedRevision: String?): AppResult<Boolean>

    fun observe(access: InboxAccess, query: InboxCacheQuery): Flow<AppResult<InboxCachePage>>

    suspend fun checkpoint(access: InboxAccess): AppResult<InboxCheckpoint?>

    suspend fun begin(checkpoint: InboxCheckpoint, expectedGeneration: String?): AppResult<Boolean>

    /** Content and progress publish together, conditional on the previous generation/revision. */
    suspend fun commit(
        previous: InboxCheckpoint,
        next: InboxCheckpoint,
        entries: List<InboxReplicaEntry>,
    ): AppResult<Boolean>

    suspend fun clear(access: InboxAccess): AppResult<Boolean>
}
