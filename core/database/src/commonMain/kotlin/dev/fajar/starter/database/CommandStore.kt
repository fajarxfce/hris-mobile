package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

/**
 * Raw durable command storage, separate from disposable account caches. Every scoped operation
 * checks the active session/account in the same transaction. No HTTP, retries or command policy.
 */
interface CommandStore {
    /** Complete partition observation, bounded by the store's 128-command account capacity. */
    fun observeAll(scope: CommandScope, partition: String): Flow<List<CommandRecord>>

    fun observe(
        scope: CommandScope,
        partition: String,
        resourceId: String? = null,
        after: String? = null,
        limit: Int = 50,
    ): Flow<List<CommandRecord>>

    suspend fun get(scope: CommandScope, partition: String, id: String): CommandRecord?

    /** One retained command per resource; identical insertion can recover a local response loss. */
    suspend fun insert(scope: CommandScope, record: CommandRecord): CommandInsertResult

    suspend fun update(
        scope: CommandScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        state: String,
    ): Boolean

    suspend fun remove(
        scope: CommandScope,
        partition: String,
        id: String,
        expectedRevision: Long,
    ): Boolean

    /** Exact owned completion read. Active observations/get deliberately exclude archives. */
    suspend fun archived(scope: CommandScope, partition: String, id: String): CommandArchive?

    /**
     * Atomically copies the exact current command plus opaque completion evidence to immutable
     * storage and removes its pending row. Caller owns completion policy and encryption. A replay
     * succeeds only for the same complete record/evidence. Future insertion of this ID cannot
     * recreate pending work; identical immutable insertion returns AlreadyStored, requiring an
     * archive read.
     */
    suspend fun archive(scope: CommandScope, expected: CommandRecord, evidence: String): Boolean

    /** Only an existence bit, used to avoid replacing a lost key over retained ciphertext. */
    suspend fun containsRecords(): Boolean
}
