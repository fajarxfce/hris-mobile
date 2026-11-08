package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Exact retained identity, including authenticated completion before or during subscription. */
interface CollaborationMessageProgressRepository {
    fun observeRecord(
        access: CollaborationLocalAccess,
        id: String,
    ): Flow<AppResult<CollaborationMessageRecord?>>
}
