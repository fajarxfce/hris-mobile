package dev.fajar.starter.identity.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.entities.SessionIdentity
import kotlinx.coroutines.flow.Flow

/** Published process identity only. Observation never restores credentials or acquires storage. */
interface SessionIdentityRepository {
    fun observePublished(): Flow<AppResult<SessionIdentity?>>
}
