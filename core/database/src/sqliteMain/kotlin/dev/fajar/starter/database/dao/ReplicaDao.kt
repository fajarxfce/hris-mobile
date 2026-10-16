package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.*
import dev.fajar.starter.database.entities.*
import kotlinx.serialization.json.Json

@Dao
abstract class ReplicaDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM replica_generations WHERE substr(partition, 1, length(:prefix)) = :prefix)"
    )
    abstract suspend fun containsRecords(prefix: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM account_scope WHERE id = 1 AND sessionId = :sessionId AND (:accountKey IS NULL OR accountKey = :accountKey))"
    )
    protected abstract suspend fun owns(sessionId: String, accountKey: String?): Boolean

    @Query("SELECT * FROM replica_heads WHERE ownerKey = :ownerKey AND partition = :partition")
    protected abstract suspend fun head(ownerKey: String, partition: String): ReplicaHeadEntity?

    @Query(
        "SELECT * FROM replica_generations WHERE ownerKey = :ownerKey AND partition = :partition AND generation = :generation"
    )
    protected abstract suspend fun generation(
        ownerKey: String,
        partition: String,
        generation: String,
    ): ReplicaGenerationEntity?

    @Query(
        "SELECT * FROM replica_documents WHERE ownerKey = :ownerKey AND partition = :partition AND generation = :generation AND id = :id"
    )
    protected abstract suspend fun record(
        ownerKey: String,
        partition: String,
        generation: String,
        id: String,
    ): ReplicaDocumentEntity?

    @Query(
        "SELECT * FROM replica_documents WHERE ownerKey = :ownerKey AND partition = :partition AND generation = :generation AND payload IS NOT NULL AND (:after IS NULL OR sortKey > :after) ORDER BY sortKey LIMIT :limit"
    )
    protected abstract suspend fun records(
        ownerKey: String,
        partition: String,
        generation: String,
        after: String?,
        limit: Int,
    ): List<ReplicaDocumentEntity>

    @Query(
        "SELECT d.* FROM replica_tags AS t JOIN replica_documents AS d ON d.ownerKey = t.ownerKey AND d.partition = t.partition AND d.generation = t.generation AND d.id = t.id WHERE t.ownerKey = :ownerKey AND t.partition = :partition AND t.generation = :generation AND t.tag = :tag AND (:after IS NULL OR t.sortKey > :after) ORDER BY t.sortKey LIMIT :limit"
    )
    protected abstract suspend fun taggedRecords(
        ownerKey: String,
        partition: String,
        generation: String,
        tag: String,
        after: String?,
        limit: Int,
    ): List<ReplicaDocumentEntity>

    @Upsert protected abstract suspend fun write(head: ReplicaHeadEntity)

    @Upsert protected abstract suspend fun write(generation: ReplicaGenerationEntity)

    @Insert protected abstract suspend fun insert(document: ReplicaDocumentEntity)

    @Update protected abstract suspend fun update(document: ReplicaDocumentEntity)

    @Insert protected abstract suspend fun insert(tags: List<ReplicaTagEntity>)

    @Query(
        "DELETE FROM replica_tags WHERE ownerKey = :ownerKey AND partition = :partition AND generation = :generation AND id = :id"
    )
    protected abstract suspend fun deleteTags(
        ownerKey: String,
        partition: String,
        generation: String,
        id: String,
    )

    @Query(
        "DELETE FROM replica_generations WHERE ownerKey = :ownerKey AND partition = :partition AND generation = :generation"
    )
    protected abstract suspend fun deleteGeneration(
        ownerKey: String,
        partition: String,
        generation: String,
    )

    @Query(
        "DELETE FROM replica_generations WHERE ownerKey = :ownerKey AND partition = :partition AND generation != :keep"
    )
    protected abstract suspend fun deleteOtherGenerations(
        ownerKey: String,
        partition: String,
        keep: String,
    )

    @Query("DELETE FROM replica_generations WHERE ownerKey = :ownerKey AND partition = :partition")
    protected abstract suspend fun deleteGenerations(ownerKey: String, partition: String)

    @Query("DELETE FROM replica_heads WHERE ownerKey = :ownerKey AND partition = :partition")
    protected abstract suspend fun deleteHead(ownerKey: String, partition: String)

    @Transaction
    open suspend fun checkpoint(
        scope: ReplicaScope,
        partition: String,
        publishedOnly: Boolean = false,
    ): ReplicaCheckpoint? {
        if (!owns(scope.sessionId, scope.accountKey)) return null
        val ownerKey = scope.accountKey.orEmpty()
        val head = head(ownerKey, partition) ?: return null
        val selected =
            (if (publishedOnly) head.activeGeneration
            else head.stagingGeneration ?: head.activeGeneration) ?: return null
        val row = generation(ownerKey, partition, selected) ?: return null
        return ReplicaCheckpoint(
            ReplicaHandle(scope, partition, selected),
            row.revision,
            row.checkpoint,
            head.activeGeneration == selected,
        )
    }

    @Transaction
    open suspend fun replace(
        scope: ReplicaScope,
        partition: String,
        expected: ReplicaCheckpoint?,
        snapshot: ReplicaSnapshot?,
    ): Boolean {
        if (!owns(scope.sessionId, scope.accountKey) || checkpoint(scope, partition) != expected)
            return false
        val ownerKey = scope.accountKey.orEmpty()
        if (snapshot != null && generation(ownerKey, partition, snapshot.generation) != null)
            return false
        deleteHead(ownerKey, partition)
        deleteGenerations(ownerKey, partition)
        if (snapshot != null) {
            write(
                ReplicaGenerationEntity(
                    ownerKey,
                    partition,
                    snapshot.generation,
                    0,
                    snapshot.checkpoint,
                )
            )
            for (document in snapshot.documents) {
                insert(
                    ReplicaDocumentEntity(
                        ownerKey,
                        partition,
                        snapshot.generation,
                        document.id,
                        document.version,
                        document.sortKey,
                        document.payload,
                        Json.encodeToString(document.tags.sorted()),
                    )
                )
                insert(
                    document.tags.map {
                        ReplicaTagEntity(
                            ownerKey,
                            partition,
                            snapshot.generation,
                            document.id,
                            it,
                            document.sortKey,
                        )
                    }
                )
            }
            write(ReplicaHeadEntity(ownerKey, partition, snapshot.generation, null))
        }
        return true
    }

    @Transaction
    open suspend fun begin(
        scope: ReplicaScope,
        partition: String,
        generation: String,
        payload: String,
        expectedGeneration: String?,
    ): Boolean {
        if (!owns(scope.sessionId, scope.accountKey)) return false
        val ownerKey = scope.accountKey.orEmpty()
        val previous = head(ownerKey, partition)
        if ((previous?.stagingGeneration ?: previous?.activeGeneration) != expectedGeneration)
            return false
        if (generation(ownerKey, partition, generation) != null) return false
        previous?.stagingGeneration?.let { deleteGeneration(ownerKey, partition, it) }
        write(ReplicaGenerationEntity(ownerKey, partition, generation, 0, payload))
        write(ReplicaHeadEntity(ownerKey, partition, previous?.activeGeneration, generation))
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
        if (!owns(handle.scope.sessionId, handle.scope.accountKey)) return false
        val ownerKey = handle.scope.accountKey.orEmpty()
        val head = head(ownerKey, handle.partition) ?: return false
        if ((head.stagingGeneration ?: head.activeGeneration) != handle.generation) return false
        val current = generation(ownerKey, handle.partition, handle.generation) ?: return false
        if (current.revision != expectedRevision) return false
        for (document in documents) {
            val previous = record(ownerKey, handle.partition, handle.generation, document.id)
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
                    ownerKey,
                    handle.partition,
                    handle.generation,
                    document.id,
                    document.version,
                    document.sortKey,
                    document.payload,
                    Json.encodeToString(document.tags.sorted()),
                )
            deleteTags(ownerKey, handle.partition, handle.generation, document.id)
            if (previous == null) insert(row) else update(row)
            insert(
                document.tags.map {
                    ReplicaTagEntity(
                        ownerKey,
                        handle.partition,
                        handle.generation,
                        document.id,
                        it,
                        document.sortKey,
                    )
                }
            )
        }
        write(current.copy(revision = expectedRevision + 1, checkpoint = payload))
        if (publish) {
            write(ReplicaHeadEntity(ownerKey, handle.partition, handle.generation, null))
            deleteOtherGenerations(ownerKey, handle.partition, handle.generation)
        }
        return true
    }

    @Transaction
    open suspend fun page(
        scope: ReplicaScope,
        partition: String,
        after: String?,
        limit: Int,
    ): ReplicaPage {
        if (!owns(scope.sessionId, scope.accountKey)) return ReplicaPage(null, emptyList())
        val ownerKey = scope.accountKey.orEmpty()
        val active =
            head(ownerKey, partition)?.activeGeneration ?: return ReplicaPage(null, emptyList())
        val row = generation(ownerKey, partition, active) ?: return ReplicaPage(null, emptyList())
        return ReplicaPage(
            ReplicaCheckpoint(
                ReplicaHandle(scope, partition, active),
                row.revision,
                row.checkpoint,
                true,
            ),
            records(ownerKey, partition, active, after, limit).map { it.toReplicaDocument() },
        )
    }

    @Transaction
    open suspend fun document(
        scope: ReplicaScope,
        partition: String,
        id: String,
    ): ReplicaDocument? {
        if (!owns(scope.sessionId, scope.accountKey)) return null
        val ownerKey = scope.accountKey.orEmpty()
        val active = head(ownerKey, partition)?.activeGeneration ?: return null
        return record(ownerKey, partition, active, id)
            ?.takeIf { it.payload != null }
            ?.toReplicaDocument()
    }

    @Transaction
    open suspend fun clear(scope: ReplicaScope, partition: String): Boolean {
        if (!owns(scope.sessionId, scope.accountKey)) return false
        val ownerKey = scope.accountKey.orEmpty()
        deleteHead(ownerKey, partition)
        deleteGenerations(ownerKey, partition)
        return true
    }

    @Transaction
    open suspend fun documentPage(scope: ReplicaScope, partition: String, id: String): ReplicaPage {
        if (!owns(scope.sessionId, scope.accountKey)) return ReplicaPage(null, emptyList())
        val ownerKey = scope.accountKey.orEmpty()
        val active =
            head(ownerKey, partition)?.activeGeneration ?: return ReplicaPage(null, emptyList())
        val row = generation(ownerKey, partition, active) ?: return ReplicaPage(null, emptyList())
        val item = record(ownerKey, partition, active, id)?.takeIf { it.payload != null }
        return ReplicaPage(
            ReplicaCheckpoint(
                ReplicaHandle(scope, partition, active),
                row.revision,
                row.checkpoint,
                true,
            ),
            listOfNotNull(item?.toReplicaDocument()),
        )
    }

    @Transaction
    open suspend fun queryPages(
        scope: ReplicaScope,
        partition: String,
        queries: List<ReplicaQuery>,
    ): ReplicaQueryPage {
        val empty = ReplicaQueryPage(null, queries.map { emptyList() })
        if (!owns(scope.sessionId, scope.accountKey)) return empty
        val ownerKey = scope.accountKey.orEmpty()
        val active = head(ownerKey, partition)?.activeGeneration ?: return empty
        val row = generation(ownerKey, partition, active) ?: return empty
        return ReplicaQueryPage(
            ReplicaCheckpoint(
                ReplicaHandle(scope, partition, active),
                row.revision,
                row.checkpoint,
                true,
            ),
            queries.map { query ->
                val documents =
                    if (query.tag == null)
                        records(ownerKey, partition, active, query.after, query.limit)
                    else
                        taggedRecords(
                            ownerKey,
                            partition,
                            active,
                            query.tag,
                            query.after,
                            query.limit,
                        )
                documents.map { it.toReplicaDocument() }
            },
        )
    }
}
