package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Raw installed-client observation; the use case owns live admission and durable intent. */
interface CollaborationSpaceRoomKeyRepository {
    /**
     * One explicit registration under an existing exact source; no client, sync or transport
     * creation.
     */
    suspend fun startKeyObservation(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
    ): AppResult<Unit>

    /** Reads only. Collection never registers, acquires, retries, or acknowledges native work. */
    fun observeKeys(
        runtime: CollaborationSpaceRuntimeReady,
        source: CollaborationSpaceIncomingSource,
    ): Flow<AppResult<CollaborationSpaceRoomKeyBatch>>

    /**
     * Releases only the matching current receipt under the same live runtime/source. The caller
     * must confirm durable recovery intent first. False means no matching receipt was consumed;
     * neither a stale receipt nor an unavailable runtime can acknowledge its successor.
     */
    suspend fun acknowledgeKeys(batch: CollaborationSpaceRoomKeyBatch): AppResult<Boolean>
}
