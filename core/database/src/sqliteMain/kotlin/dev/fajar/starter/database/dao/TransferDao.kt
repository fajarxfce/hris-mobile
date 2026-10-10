package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.MAX_STORED_TRANSFER_CHUNK_BYTES
import dev.fajar.starter.database.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class TransferDao {
    @Query(
        "SELECT * FROM transfers WHERE EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId) ORDER BY createdAtEpochMillis, id"
    )
    abstract fun observe(sessionId: String): Flow<List<TransferEntity>>

    @Query(
        "SELECT * FROM transfers WHERE id = :id AND EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId)"
    )
    abstract suspend fun get(sessionId: String, id: String): TransferEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId)")
    protected abstract suspend fun ownsCache(sessionId: String): Boolean

    @Query("SELECT COUNT(*) FROM transfers") protected abstract suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM transfers")
    protected abstract suspend fun reservedBytes(): Long

    @Insert protected abstract suspend fun insert(record: TransferEntity)

    @Update protected abstract suspend fun update(record: TransferEntity)

    @Upsert protected abstract suspend fun writeChunk(record: TransferChunkEntity)

    @Query("DELETE FROM transfer_chunks WHERE transferId = :id")
    protected abstract suspend fun clearContent(id: String)

    @Query("DELETE FROM transfers WHERE id = :id") protected abstract suspend fun delete(id: String)

    @Transaction
    open suspend fun create(
        sessionId: String,
        record: TransferEntity,
        maxBytes: Long,
        maxItems: Int,
    ): Boolean {
        require(record.size >= 0 && record.version == 0L && maxBytes > 0 && maxItems > 0)
        if (
            !ownsCache(sessionId) || count() >= maxItems || record.size > maxBytes - reservedBytes()
        )
            return false
        insert(record)
        return true
    }

    @Transaction
    open suspend fun replace(
        sessionId: String,
        expectedVersion: Long,
        record: TransferEntity,
        chunk: TransferChunkEntity?,
        clearContent: Boolean,
    ): Boolean {
        val current = get(sessionId, record.id) ?: return false
        if (current.version != expectedVersion) return false
        require(current.size == record.size)
        if (chunk != null)
            require(
                chunk.transferId == record.id &&
                    chunk.offset >= 0 &&
                    chunk.bytes.isNotEmpty() &&
                    chunk.bytes.size <= MAX_STORED_TRANSFER_CHUNK_BYTES &&
                    chunk.offset + chunk.bytes.size <= record.size
            )
        if (clearContent) clearContent(record.id)
        update(record.copy(version = expectedVersion + 1))
        if (chunk != null) writeChunk(chunk)
        return true
    }

    @Query(
        "SELECT * FROM transfer_chunks WHERE transferId = :id AND offset <= :offset AND EXISTS(SELECT 1 FROM account_scope WHERE sessionId = :sessionId) ORDER BY offset DESC LIMIT 1"
    )
    abstract suspend fun chunk(sessionId: String, id: String, offset: Long): TransferChunkEntity?

    @Transaction
    open suspend fun remove(sessionId: String, id: String, expectedVersion: Long): Boolean {
        if (get(sessionId, id)?.version != expectedVersion) return false
        delete(id)
        return true
    }
}
