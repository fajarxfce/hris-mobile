package dev.fajar.starter.database

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomReplicaStore(private val database: StarterDatabase) : ReplicaStore {
    private val dao = database.replicaDao()

    override suspend fun checkpoint(sessionId: String, partition: String): ReplicaCheckpoint? {
        validateReplicaKey(sessionId, partition)
        return dao.checkpoint(sessionId, partition)
    }

    override suspend fun replace(
        sessionId: String,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
    ): Boolean {
        val retained = snapshot?.copy(documents = snapshot.documents.toList())
        validateReplicaReplacement(sessionId, partition, expected, retained)
        return dao.replace(sessionId, partition, expected, retained)
    }

    override suspend fun begin(
        sessionId: String,
        partition: String,
        generation: String,
        checkpoint: String,
        expectedGeneration: String?,
    ): Boolean {
        validateReplicaKey(sessionId, partition)
        validateReplicaGeneration(generation)
        validateReplicaCheckpoint(checkpoint)
        return dao.begin(sessionId, partition, generation, checkpoint, expectedGeneration)
    }

    override suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        checkpoint: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
    ): Boolean {
        validateReplicaKey(handle.sessionId, handle.partition)
        validateReplicaGeneration(handle.generation)
        validateReplicaCheckpoint(checkpoint)
        val batch = documents.toList()
        validateReplicaBatch(expectedRevision, batch)
        return dao.commit(handle, expectedRevision, checkpoint, batch, publish)
    }

    override fun observe(sessionId: String, partition: String, after: String?, limit: Int) =
        database.invalidationTracker
            .createFlow(
                "account_scope",
                "replica_heads",
                "replica_generations",
                "replica_documents",
            )
            .map {
                validateReplicaKey(sessionId, partition)
                validateReplicaPage(after, limit)
                dao.page(sessionId, partition, after, limit)
            }
            .distinctUntilChanged()

    override suspend fun document(
        sessionId: String,
        partition: String,
        id: String,
    ): ReplicaDocument? {
        validateReplicaKey(sessionId, partition)
        validateReplicaGeneration(id)
        return dao.document(sessionId, partition, id)
    }

    override fun observeDocument(sessionId: String, partition: String, id: String) =
        database.invalidationTracker
            .createFlow(
                "account_scope",
                "replica_heads",
                "replica_generations",
                "replica_documents",
            )
            .map {
                validateReplicaKey(sessionId, partition)
                validateReplicaGeneration(id)
                dao.documentPage(sessionId, partition, id)
            }
            .distinctUntilChanged()

    override suspend fun clear(sessionId: String, partition: String): Boolean {
        validateReplicaKey(sessionId, partition)
        return dao.clear(sessionId, partition)
    }
}
