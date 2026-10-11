package dev.fajar.starter.location.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.location.domain.entities.*

interface LocationRepository {
    suspend fun access(): AppResult<LocationAccess>

    suspend fun requestPermission(): AppResult<LocationAccess>

    suspend fun current(): AppResult<LocationFix>

    suspend fun openSettings(target: LocationSettings): AppResult<Unit>
}
