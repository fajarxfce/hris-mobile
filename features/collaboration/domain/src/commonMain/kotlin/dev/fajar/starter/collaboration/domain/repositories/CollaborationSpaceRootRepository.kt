package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Local account discovery only. Consumers must separately apply directory/runtime admission. */
interface CollaborationSpaceRootRepository {
    fun observe(access: CollaborationSpaceAccountAccess): Flow<AppResult<CollaborationSpaceRoot?>>

    suspend fun current(access: CollaborationSpaceAccountAccess): AppResult<CollaborationSpaceRoot?>

    /** First publication or exact local replay only; false never authorizes replacement. */
    suspend fun retain(
        access: CollaborationSpaceAccountAccess,
        root: CollaborationSpaceRoot,
    ): AppResult<Boolean>
}
