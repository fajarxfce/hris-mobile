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

    /** Only an existence bit, used to avoid replacing a lost key over retained ciphertext. */
    suspend fun containsRecords(): Boolean
}
