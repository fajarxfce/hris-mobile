package dev.fajar.starter.availability.domain.repositories

import dev.fajar.starter.availability.domain.entities.AppPolicy
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

interface AvailabilityRepository {
    fun observe(): Flow<AppResult<AppPolicy>>

    suspend fun current(): AppResult<AppPolicy>

    suspend fun dismiss(recommendedBuild: Long): AppResult<Unit>
}
