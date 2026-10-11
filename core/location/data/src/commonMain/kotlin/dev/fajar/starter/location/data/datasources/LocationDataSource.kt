package dev.fajar.starter.location.data.datasources

import dev.fajar.starter.location.data.dto.*

/** Raw platform operations. Permission and acquisition policy belong to use cases. */
interface LocationDataSource {
    suspend fun access(): LocationAccessDto

    suspend fun requestPermission(): LocationAccessDto

    suspend fun current(): LocationFixDto

    suspend fun openSettings(target: LocationSettingsDto): Boolean

    fun close() {}
}
