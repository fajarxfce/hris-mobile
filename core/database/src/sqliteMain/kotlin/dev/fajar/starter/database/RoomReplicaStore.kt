package dev.fajar.starter.database

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomReplicaStore(private val database: StarterDatabase) : ReplicaStore {
    private val dao = database.replicaDao()

    override suspend fun containsRecords(partitionPrefix: String): Boolean {
        require(partitionPrefix.length in 1..160 && partitionPrefix.all { it in '!'..'~' })
        return dao.containsRecords(partitionPrefix)
    }

    override suspend fun checkpoint(scope: ReplicaScope, partition: String): ReplicaCheckpoint? {
        validateReplicaKey(scope, partition)
        return dao.checkpoint(scope, partition)
    }

    override fun observeCheckpoint(scope: ReplicaScope, partition: String) =
        database.invalidationTracker
            .createFlow("account_scope", "replica_heads", "replica_generations")
            .map {
                validateReplicaKey(scope, partition)
                dao.checkpoint(scope, partition)
            }
            .distinctUntilChanged()

    override fun observePublishedCheckpoint(scope: ReplicaScope, partition: String) =
        database.invalidationTracker
            .createFlow("account_scope", "replica_heads", "replica_generations")
            .map {
                validateReplicaKey(scope, partition)
                dao.checkpoint(scope, partition, publishedOnly = true)
            }
            .distinctUntilChanged()

    override suspend fun replace(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
    ): Boolean {
        val retained =
            snapshot?.copy(documents = snapshot.documents.map { it.copy(tags = it.tags.toSet()) })
        validateReplicaReplacement(scope, partition, expected, retained)
        return dao.replace(scope, partition, expected, retained)
    }

    override suspend fun begin(
        scope: ReplicaScope,
        partition: String,
        generation: String,
        checkpoint: String,
        expectedGeneration: String?,
    ): Boolean {
        validateReplicaKey(scope, partition)
        validateReplicaGeneration(generation)
        validateReplicaCheckpoint(checkpoint)
        return dao.begin(scope, partition, generation, checkpoint, expectedGeneration)
    }

    override suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        checkpoint: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
    ): Boolean {
        validateReplicaKey(handle.scope, handle.partition)
        validateReplicaGeneration(handle.generation)
        validateReplicaCheckpoint(checkpoint)
        val batch = documents.map { it.copy(tags = it.tags.toSet()) }
        validateReplicaBatch(expectedRevision, batch)
        return dao.commit(handle, expectedRevision, checkpoint, batch, publish)
    }

    override fun observe(scope: ReplicaScope, partition: String, after: String?, limit: Int) =
        database.invalidationTracker
            .createFlow(
                "account_scope",
                "replica_heads",
                "replica_generations",
                "replica_documents",
            )
            .map {
                validateReplicaKey(scope, partition)
                validateReplicaPage(after, limit)
                dao.page(scope, partition, after, limit)
            }
            .distinctUntilChanged()

    override suspend fun document(
        scope: ReplicaScope,
        partition: String,
        id: String,
    ): ReplicaDocument? {
        validateReplicaKey(scope, partition)
        validateReplicaGeneration(id)
        return dao.document(scope, partition, id)
    }

    override fun observeQueries(
        scope: ReplicaScope,
        partition: String,
        queries: List<ReplicaQuery>,
    ) =
        queries.toList().let { retained ->
            database.invalidationTracker
                .createFlow(
                    "account_scope",
                    "replica_heads",
                    "replica_generations",
                    "replica_documents",
                    "replica_tags",
                )
                .map {
                    validateReplicaKey(scope, partition)
                    validateReplicaQueries(retained)
                    dao.queryPages(scope, partition, retained)
                }
                .distinctUntilChanged()
        }

    override fun observeDocument(scope: ReplicaScope, partition: String, id: String) =
        database.invalidationTracker
            .createFlow(
                "account_scope",
                "replica_heads",
                "replica_generations",
                "replica_documents",
            )
            .map {
                validateReplicaKey(scope, partition)
                validateReplicaGeneration(id)
                dao.documentPage(scope, partition, id)
            }
            .distinctUntilChanged()

    override suspend fun clear(scope: ReplicaScope, partition: String): Boolean {
        validateReplicaKey(scope, partition)
        return dao.clear(scope, partition)
    }
}
