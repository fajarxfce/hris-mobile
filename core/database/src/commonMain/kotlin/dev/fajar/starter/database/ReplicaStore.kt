package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

/** Raw encrypted replication storage. Callers own sync policy, encryption, and DTO mapping. */
interface ReplicaStore {
    suspend fun checkpoint(sessionId: String, partition: String): ReplicaCheckpoint?

    /** Observes sync progress, including staging, without reading document payloads. */
    fun observeCheckpoint(sessionId: String, partition: String): Flow<ReplicaCheckpoint?>

    /**
     * Publishes a complete snapshot, or removes it, only if the current checkpoint still matches.
     * Account ownership, documents and checkpoint change in the same transaction.
     */
    suspend fun replace(
        sessionId: String,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
    ): Boolean

    /** Starts a replacement without exposing its incomplete documents to readers. */
    suspend fun begin(
        sessionId: String,
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
        sessionId: String,
        partition: String,
        after: String? = null,
        limit: Int = 50,
    ): Flow<ReplicaPage>

    /** Observes one visible document and its active checkpoint in the same read transaction. */
    fun observeDocument(sessionId: String, partition: String, id: String): Flow<ReplicaPage>

    suspend fun document(sessionId: String, partition: String, id: String): ReplicaDocument?

    suspend fun clear(sessionId: String, partition: String): Boolean
}
