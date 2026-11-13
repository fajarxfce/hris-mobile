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
     * Reads only the visible generation's metadata. An incomplete replacement does not hide or
     * advance it; account ownership and the published checkpoint share one read transaction.
     */
    fun observePublishedCheckpoint(scope: ReplicaScope, partition: String): Flow<ReplicaCheckpoint?>

    /**
     * A bounded page of published metadata, ordered by partition within an exact prefix. Reads
     * share one account check and transaction; staging generations and document bodies are
     * excluded.
     */
    fun observePublishedCheckpoints(
        scope: ReplicaScope,
        partitionPrefix: String,
        afterPartition: String? = null,
        limit: Int = 50,
    ): Flow<List<ReplicaCheckpoint>>

    /**
     * Publishes a complete snapshot, or removes it, only if the current checkpoint still matches.
     * Account ownership, documents, checkpoint and an optional wake change in the same transaction.
     * A wake requires a fresh generation; capacity or current-generation conflicts return false
     * without changing either store. Cancellation may lose a committed response, never half a
     * write.
     */
    suspend fun replace(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
        wake: WorkWakeRequest? = null,
    ): Boolean

    /**
     * Applies one or two mutations to distinct partitions under the same account owner, with at
     * least one write. A Check compares the exact working checkpoint (or its absence) without
     * advancing it or changing its documents. Writes retain replacement/commit semantics and
     * 200-document, 4MiB bounds. All expected checkpoints, generations and the optional wake are
     * checked in one write transaction; any conflict changes neither partition nor wake. Database
     * failure rolls back every write. A cancelled caller may lose a committed response; it cannot
     * leave a partially committed pair.
     */
    suspend fun mutate(
        scope: ReplicaScope,
        mutations: List<ReplicaMutation>,
        wake: WorkWakeRequest? = null,
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
     * Removes only an exact unpublished generation. The active snapshot remains intact; a competing
     * continuation/publication or owner replacement makes this return false.
     */
    suspend fun discardStaging(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint,
    ): Boolean

    /**
     * Reads up to 200 distinct identities from the working generation (staging, else active),
     * including tombstones, with its checkpoint in one owned transaction. Ordered by id. This is an
     * acquisition aid; visible projections must use the published read methods.
     */
    suspend fun workingDocuments(
        scope: ReplicaScope,
        partition: String,
        ids: List<String>,
    ): ReplicaPage

    /**
     * Documents, the next checkpoint and an optional wake commit together, conditional on the
     * observed generation/revision. The wake has the same conflict/rollback contract as replace.
     */
    suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        checkpoint: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
        wake: WorkWakeRequest? = null,
    ): Boolean

    fun observe(
        scope: ReplicaScope,
        partition: String,
        after: String? = null,
        limit: Int = 50,
    ): Flow<ReplicaPage>

    /** Bounded indexed reads sharing one generation, revision and ownership check. */
    fun observeQueries(
        scope: ReplicaScope,
        partition: String,
        queries: List<ReplicaQuery>,
    ): Flow<ReplicaQueryPage>

    /** Observes one visible document and its active checkpoint in the same read transaction. */
    fun observeDocument(scope: ReplicaScope, partition: String, id: String): Flow<ReplicaPage>

    suspend fun document(scope: ReplicaScope, partition: String, id: String): ReplicaDocument?

    suspend fun clear(scope: ReplicaScope, partition: String): Boolean
}
