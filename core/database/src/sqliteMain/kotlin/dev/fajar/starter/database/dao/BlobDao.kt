package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.*
import dev.fajar.starter.database.entities.BlobChunkEntity
import dev.fajar.starter.database.entities.BlobEntity

@Dao
abstract class BlobDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM account_scope WHERE id = 1 AND sessionId = :session AND accountKey = :account)"
    )
    protected abstract suspend fun admitted(session: String, account: String): Boolean

    @Query(
        "SELECT * FROM durable_blobs WHERE accountKey = :account AND partition = :partition AND id = :id"
    )
    protected abstract suspend fun record(
        account: String,
        partition: String,
        id: String,
    ): BlobEntity?

    @Query(
        "SELECT * FROM durable_blobs WHERE accountKey = :account AND partition = :partition AND (:after IS NULL OR id > :after) ORDER BY id LIMIT :limit"
    )
    protected abstract suspend fun records(
        account: String,
        partition: String,
        after: String?,
        limit: Int,
    ): List<BlobEntity>

    @Query(
        "SELECT * FROM durable_blob_chunks WHERE accountKey = :account AND partition = :partition AND id = :id AND offset = :offset"
    )
    protected abstract suspend fun block(
        account: String,
        partition: String,
        id: String,
        offset: Long,
    ): BlobChunkEntity?

    @Query("SELECT COUNT(*) FROM durable_blobs") protected abstract suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM durable_blobs WHERE accountKey = :account")
    protected abstract suspend fun countAccount(account: String): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM durable_blobs")
    protected abstract suspend fun reservedAll(): Long

    @Query("SELECT COALESCE(SUM(size), 0) FROM durable_blobs WHERE accountKey = :account")
    protected abstract suspend fun reservedAccount(account: String): Long

    @Query("SELECT EXISTS(SELECT 1 FROM durable_blobs)")
    abstract suspend fun containsRecords(): Boolean

    @Insert protected abstract suspend fun create(entry: BlobEntity)

    @Insert protected abstract suspend fun write(chunk: BlobChunkEntity)

    @Update protected abstract suspend fun change(entry: BlobEntity): Int

    @Query(
        "DELETE FROM durable_blobs WHERE accountKey = :account AND partition = :partition AND id = :id AND revision = :revision"
    )
    protected abstract suspend fun delete(
        account: String,
        partition: String,
        id: String,
        revision: Long,
    ): Int

    @Transaction
    open suspend fun page(
        session: String,
        account: String,
        partition: String,
        after: String?,
        limit: Int,
    ): List<BlobEntity> =
        if (admitted(session, account)) records(account, partition, after, limit) else emptyList()

    @Transaction
    open suspend fun get(
        session: String,
        account: String,
        partition: String,
        id: String,
    ): BlobEntity? = if (admitted(session, account)) record(account, partition, id) else null

    @Transaction
    open suspend fun chunk(
        session: String,
        account: String,
        partition: String,
        id: String,
        offset: Long,
    ): BlobChunkEntity? =
        if (admitted(session, account)) block(account, partition, id, offset) else null

    @Transaction
    open suspend fun insert(
        session: String,
        entry: BlobEntity,
        maxBytes: Long,
        maxItems: Int,
    ): BlobInsertResult {
        if (!admitted(session, entry.accountKey)) return BlobInsertResult.ScopeChanged
        val previous = record(entry.accountKey, entry.partition, entry.id)
        if (previous != null) {
            require(previous.size == entry.size && previous.metadata == entry.metadata) {
                "Blob identity cannot change."
            }
            return BlobInsertResult.AlreadyStored
        }
        if (
            countAll() >= MAX_BLOB_TOTAL_ITEMS ||
                countAccount(entry.accountKey) >= maxItems ||
                entry.size > MAX_BLOB_TOTAL_BYTES - reservedAll() ||
                entry.size > maxBytes - reservedAccount(entry.accountKey)
        )
            return BlobInsertResult.CapacityExceeded
        create(entry)
        return BlobInsertResult.Stored
    }

    @Transaction
    open suspend fun append(session: String, chunk: BlobChunkEntity, revision: Long): Boolean {
        if (!admitted(session, chunk.accountKey)) return false
        val current = record(chunk.accountKey, chunk.partition, chunk.id) ?: return false
        if (current.revision != revision || current.completion != null) return false
        require(
            current.storedBytes == chunk.offset &&
                chunk.length.toLong() ==
                    minOf(BLOB_BLOCK_BYTES.toLong(), current.size - current.storedBytes)
        ) {
            "Invalid blob extent."
        }
        write(chunk)
        check(
            change(
                current.copy(
                    storedBytes = current.storedBytes + chunk.length,
                    revision = revision + 1,
                )
            ) == 1
        )
        return true
    }

    @Transaction
    open suspend fun complete(
        session: String,
        account: String,
        partition: String,
        id: String,
        revision: Long,
        proof: String,
    ): Boolean {
        if (!admitted(session, account)) return false
        val current = record(account, partition, id) ?: return false
        if (
            current.revision != revision ||
                current.completion != null ||
                current.storedBytes != current.size
        )
            return false
        return change(current.copy(completion = proof, revision = revision + 1)) == 1
    }

    @Transaction
    open suspend fun updateState(
        session: String,
        account: String,
        partition: String,
        id: String,
        revision: Long,
        state: String,
    ): Boolean {
        if (!admitted(session, account)) return false
        val current = record(account, partition, id) ?: return false
        if (current.revision != revision || current.completion == null) return false
        return change(current.copy(state = state, revision = revision + 1)) == 1
    }

    @Transaction
    open suspend fun remove(
        session: String,
        account: String,
        partition: String,
        id: String,
        revision: Long,
    ): Boolean = admitted(session, account) && delete(account, partition, id, revision) == 1
}
