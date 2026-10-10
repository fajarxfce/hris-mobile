package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

interface TransferStore {
    fun observe(sessionId: String): Flow<List<TransferRecord>>

    suspend fun get(sessionId: String, id: String): TransferRecord?

    /** Account ownership, uniqueness and caller-provided storage limits are checked atomically. */
    suspend fun create(
        sessionId: String,
        record: TransferRecord,
        maxBytes: Long,
        maxItems: Int,
    ): Boolean

    /**
     * Metadata and optional content block commit together only while the expected version matches.
     */
    suspend fun replace(
        sessionId: String,
        expectedVersion: Long,
        record: TransferRecord,
        chunk: TransferChunkRecord? = null,
        clearContent: Boolean = false,
    ): Boolean

    suspend fun chunk(sessionId: String, id: String, offset: Long): TransferChunkRecord?

    suspend fun remove(sessionId: String, id: String, expectedVersion: Long): Boolean
}
