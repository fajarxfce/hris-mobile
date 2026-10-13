package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

/** Raw encrypted replication storage. Callers own sync policy, encryption, and DTO mapping. */
interface ReplicaStore {
    /** Checks retained generations, including inactive accounts, before creating a content key. */
    suspend fun containsRecords(partitionPrefix: String): Boolean

    suspend fun checkpoint(scope: ReplicaScope, partition: String): ReplicaCheckpoint?

    /** Observes sync progress, including staging, without reading document payloads. */
    fun observeCheckpoint(scope: ReplicaScope, partition: String): Flow<ReplicaCheckpoint?>

    /**
     * Publishes a complete snapshot, or removes it, only if the current checkpoint still matches.
     * Account ownership, documents and checkpoint change in the same transaction.
     */
    suspend fun replace(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
    ): Boolean

    /** Starts a replacement without exposing its incomplete documents to readers. */
    suspend fun begin(
        scope: ReplicaScope,
        partition: String,
        generation: String,
        checkpoint: String,
        expectedGeneration: String?,
    ): Boolean

    /**
     * Documents and the next checkpoint commit together, conditional on the observed generation.
     */
    suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        checkpoint: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
    ): Boolean

    fun observe(
        scope: ReplicaScope,
        partition: String,
        after: String? = null,
        limit: Int = 50,
    ): Flow<ReplicaPage>

    /** Observes one visible document and its active checkpoint in the same read transaction. */
    fun observeDocument(scope: ReplicaScope, partition: String, id: String): Flow<ReplicaPage>

    suspend fun document(scope: ReplicaScope, partition: String, id: String): ReplicaDocument?

    suspend fun clear(scope: ReplicaScope, partition: String): Boolean
}
