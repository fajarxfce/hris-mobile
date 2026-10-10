package dev.fajar.starter.featureflags.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.featureflags.domain.entities.FlagSnapshot
import kotlinx.coroutines.flow.Flow

interface FeatureFlagRepository {
    fun observe(): Flow<AppResult<FlagSnapshot>>

    suspend fun snapshot(): AppResult<FlagSnapshot>

    suspend fun refresh(fetchedAtEpochMillis: Long): AppResult<Unit>

    suspend fun setOverride(key: String, value: Boolean?): AppResult<Unit>
}
