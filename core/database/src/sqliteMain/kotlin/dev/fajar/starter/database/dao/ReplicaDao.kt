package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.*
import dev.fajar.starter.database.entities.*

@Dao
abstract class ReplicaDao {
    @Query("SELECT EXISTS(SELECT 1 FROM account_scope WHERE id = 1 AND sessionId = :sessionId)")
    protected abstract suspend fun owns(sessionId: String): Boolean

    @Query("SELECT * FROM replica_heads WHERE partition = :partition")
    protected abstract suspend fun head(partition: String): ReplicaHeadEntity?

    @Query(
        "SELECT * FROM replica_generations WHERE partition = :partition AND generation = :generation"
    )
    protected abstract suspend fun generation(
        partition: String,
        generation: String,
    ): ReplicaGenerationEntity?

    @Query(
        "SELECT * FROM replica_documents WHERE partition = :partition AND generation = :generation AND id = :id"
    )
    protected abstract suspend fun record(
        partition: String,
        generation: String,
        id: String,
    ): ReplicaDocumentEntity?

    @Query(
        "SELECT * FROM replica_documents WHERE partition = :partition AND generation = :generation AND payload IS NOT NULL AND (:after IS NULL OR sortKey > :after) ORDER BY sortKey LIMIT :limit"
    )
    protected abstract suspend fun records(
        partition: String,
        generation: String,
        after: String?,
        limit: Int,
    ): List<ReplicaDocumentEntity>

    @Upsert protected abstract suspend fun write(head: ReplicaHeadEntity)

    @Upsert protected abstract suspend fun write(generation: ReplicaGenerationEntity)

    @Insert protected abstract suspend fun insert(document: ReplicaDocumentEntity)

    @Update protected abstract suspend fun update(document: ReplicaDocumentEntity)

    @Query(
        "DELETE FROM replica_generations WHERE partition = :partition AND generation = :generation"
    )
    protected abstract suspend fun deleteGeneration(partition: String, generation: String)

    @Query("DELETE FROM replica_generations WHERE partition = :partition AND generation != :keep")
    protected abstract suspend fun deleteOtherGenerations(partition: String, keep: String)

    @Query("DELETE FROM replica_generations WHERE partition = :partition")
    protected abstract suspend fun deleteGenerations(partition: String)

    @Query("DELETE FROM replica_heads WHERE partition = :partition")
    protected abstract suspend fun deleteHead(partition: String)

    @Transaction
    open suspend fun checkpoint(sessionId: String, partition: String): ReplicaCheckpoint? {
        if (!owns(sessionId)) return null
        val head = head(partition) ?: return null
        val selected = head.stagingGeneration ?: head.activeGeneration ?: return null
        val row = generation(partition, selected) ?: return null
        return ReplicaCheckpoint(
            ReplicaHandle(sessionId, partition, selected),
            row.revision,
            row.checkpoint,
            head.activeGeneration == selected,
        )
    }

    @Transaction
    open suspend fun begin(
        sessionId: String,
        partition: String,
        generation: String,
        payload: String,
        expectedGeneration: String?,
    ): Boolean {
        if (!owns(sessionId)) return false
        val previous = head(partition)
        if ((previous?.stagingGeneration ?: previous?.activeGeneration) != expectedGeneration)
            return false
        if (generation(partition, generation) != null) return false
        previous?.stagingGeneration?.let { deleteGeneration(partition, it) }
        write(ReplicaGenerationEntity(partition, generation, sessionId, 0, payload))
        write(ReplicaHeadEntity(partition, previous?.activeGeneration, generation))
        return true
    }

    @Transaction
    open suspend fun commit(
        handle: ReplicaHandle,
        expectedRevision: Long,
        payload: String,
        documents: List<ReplicaDocument>,
        publish: Boolean,
    ): Boolean {
        if (!owns(handle.sessionId)) return false
        val head = head(handle.partition) ?: return false
        if ((head.stagingGeneration ?: head.activeGeneration) != handle.generation) return false
        val current = generation(handle.partition, handle.generation) ?: return false
        if (current.sessionId != handle.sessionId || current.revision != expectedRevision)
            return false
        for (document in documents) {
            val previous = record(handle.partition, handle.generation, document.id)
            if (
                previous != null &&
                    (previous.version > document.version ||
                        (previous.version == document.version &&
                            previous.payload == null &&
                            document.payload != null))
            )
                continue
            val row =
                ReplicaDocumentEntity(
                    handle.partition,
                    handle.generation,
                    document.id,
                    document.version,
                    document.sortKey,
                    document.payload,
                )
            if (previous == null) insert(row) else update(row)
        }
        write(current.copy(revision = expectedRevision + 1, checkpoint = payload))
        if (publish) {
            write(ReplicaHeadEntity(handle.partition, handle.generation, null))
            deleteOtherGenerations(handle.partition, handle.generation)
        }
        return true
    }

    @Transaction
    open suspend fun page(
        sessionId: String,
        partition: String,
        after: String?,
        limit: Int,
    ): ReplicaPage {
        if (!owns(sessionId)) return ReplicaPage(null, emptyList())
        val active = head(partition)?.activeGeneration ?: return ReplicaPage(null, emptyList())
        val row = generation(partition, active) ?: return ReplicaPage(null, emptyList())
        return ReplicaPage(
            ReplicaCheckpoint(
                ReplicaHandle(sessionId, partition, active),
                row.revision,
                row.checkpoint,
                true,
            ),
            records(partition, active, after, limit).map {
                ReplicaDocument(it.id, it.version, it.sortKey, it.payload)
            },
        )
    }

    @Transaction
    open suspend fun document(sessionId: String, partition: String, id: String): ReplicaDocument? {
        if (!owns(sessionId)) return null
        val active = head(partition)?.activeGeneration ?: return null
        return record(partition, active, id)
            ?.takeIf { it.payload != null }
            ?.let { ReplicaDocument(it.id, it.version, it.sortKey, it.payload) }
    }

    @Transaction
    open suspend fun clear(sessionId: String, partition: String): Boolean {
        if (!owns(sessionId)) return false
        deleteHead(partition)
        deleteGenerations(partition)
        return true
    }

    @Transaction
    open suspend fun documentPage(sessionId: String, partition: String, id: String): ReplicaPage {
        if (!owns(sessionId)) return ReplicaPage(null, emptyList())
        val active = head(partition)?.activeGeneration ?: return ReplicaPage(null, emptyList())
        val row = generation(partition, active) ?: return ReplicaPage(null, emptyList())
        val item = record(partition, active, id)?.takeIf { it.payload != null }
        return ReplicaPage(
            ReplicaCheckpoint(
                ReplicaHandle(sessionId, partition, active),
                row.revision,
                row.checkpoint,
                true,
            ),
            listOfNotNull(item?.let { ReplicaDocument(it.id, it.version, it.sortKey, it.payload) }),
        )
    }
}
