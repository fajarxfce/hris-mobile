package dev.fajar.starter.identity.domain.push.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.identity.domain.push.entities.NativePushState

interface NativePushStateRepository {
    val persistent: Boolean

    suspend fun read(): AppResult<NativePushState>

    /** False means another local transition committed; callers must reload. */
    suspend fun compareAndSet(
        expected: NativePushState,
        updated: NativePushState,
    ): AppResult<Boolean>
}
