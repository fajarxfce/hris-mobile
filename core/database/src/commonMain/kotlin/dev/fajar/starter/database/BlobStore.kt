package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

/**
 * Durable opaque file storage. Ownership, reservation, block progress and revision checks are
 * transactional. The caller owns encryption, import lifetime, completion proof and deletion policy.
 */
interface BlobStore {
    fun observe(
        scope: BlobScope,
        partition: String,
        after: String? = null,
        limit: Int = 20,
    ): Flow<List<BlobRecord>>

    suspend fun get(scope: BlobScope, partition: String, id: String): BlobRecord?

    /**
     * Reserves the declared size before accepting any blocks. Identical creation is recoverable.
     */
    suspend fun insert(
        scope: BlobScope,
        record: BlobRecord,
        maxBytes: Long,
        maxItems: Int,
    ): BlobInsertResult

    /** Appends exactly one 64 KiB block, except for the final remainder. */
    suspend fun append(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        chunk: BlobChunkRecord,
    ): Boolean

    /**
     * Freezes a fully stored file. A completed file cannot receive further blocks or a new proof.
     */
    suspend fun complete(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        completion: String,
    ): Boolean

    /** Replaces an opaque checkpoint after completion, preserving the original file and proof. */
    suspend fun updateState(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        state: String,
    ): Boolean

    suspend fun chunk(
        scope: BlobScope,
        partition: String,
        id: String,
        offset: Long,
    ): BlobChunkRecord?

    suspend fun remove(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
    ): Boolean

    /**
     * Existence only, so a caller can reject replacement of a lost key over retained ciphertext.
     */
    suspend fun containsRecords(): Boolean
}
