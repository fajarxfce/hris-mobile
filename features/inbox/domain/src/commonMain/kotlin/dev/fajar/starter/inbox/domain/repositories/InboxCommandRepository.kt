package dev.fajar.starter.inbox.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.inbox.domain.entities.*
import kotlinx.coroutines.flow.Flow

interface InboxCommandRepository {
    fun observe(access: InboxAccess, itemId: String): Flow<AppResult<InboxCommandRecord?>>

    suspend fun find(access: InboxAccess, id: String): AppResult<InboxCommandRecord?>

    suspend fun page(access: InboxAccess, cursor: String?): AppResult<InboxCommandPage>

    suspend fun insert(access: InboxAccess, command: InboxCommand): AppResult<InboxCommandAdmission>

    suspend fun update(
        access: InboxAccess,
        expected: InboxCommandRecord,
        outcome: InboxCommandOutcome,
        attempts: Int = expected.attempts,
    ): AppResult<Boolean>

    suspend fun remove(access: InboxAccess, expected: InboxCommandRecord): AppResult<Boolean>
}
