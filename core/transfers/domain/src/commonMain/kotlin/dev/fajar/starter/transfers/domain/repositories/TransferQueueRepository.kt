package dev.fajar.starter.transfers.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.transfers.domain.entities.Transfer
import kotlinx.coroutines.flow.Flow

interface TransferQueueRepository {
    fun observe(sessionId: String): Flow<AppResult<List<Transfer>>>

    suspend fun get(sessionId: String, id: String): AppResult<Transfer?>

    suspend fun create(
        sessionId: String,
        transfer: Transfer,
        maxBytes: Long,
        maxItems: Int,
    ): AppResult<Boolean>

    /**
     * CAS metadata and optional chunk in one transaction. Returns the new checkpoint or null if
     * stale.
     */
    suspend fun commit(
        sessionId: String,
        transfer: Transfer,
        chunk: ByteArray? = null,
        clearContent: Boolean = false,
    ): AppResult<Transfer?>

    /**
     * Reads at most one stored chunk, starting at offset (including a partially accepted upload).
     */
    suspend fun read(sessionId: String, id: String, offset: Long): AppResult<ByteArray>

    suspend fun remove(sessionId: String, id: String, version: Long): AppResult<Boolean>
}
