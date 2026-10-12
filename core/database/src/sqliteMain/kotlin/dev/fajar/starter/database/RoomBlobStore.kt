package dev.fajar.starter.database

import dev.fajar.starter.database.entities.BlobChunkEntity
import dev.fajar.starter.database.entities.BlobEntity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomBlobStore(private val database: StarterDatabase) : BlobStore {
    private val dao = database.blobDao()

    override fun observe(scope: BlobScope, partition: String, after: String?, limit: Int) =
        database.invalidationTracker
            .createFlow("account_scope", "durable_blobs")
            .map {
                validateBlobScope(scope, partition)
                validateBlobPage(after, limit)
                dao.page(scope.sessionId, scope.accountKey, partition, after, limit).map {
                    it.toRecord()
                }
            }
            .distinctUntilChanged()

    override suspend fun get(scope: BlobScope, partition: String, id: String): BlobRecord? {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        return dao.get(scope.sessionId, scope.accountKey, partition, id)?.toRecord()
    }

    override suspend fun insert(
        scope: BlobScope,
        record: BlobRecord,
        maxBytes: Long,
        maxItems: Int,
    ): BlobInsertResult {
        validateBlobInsert(scope, record, maxBytes, maxItems)
        return dao.insert(
            scope.sessionId,
            BlobEntity(
                scope.accountKey,
                record.partition,
                record.id,
                record.size,
                record.metadata,
                record.storedBytes,
                record.completion,
                record.revision,
                record.state,
            ),
            maxBytes,
            maxItems,
        )
    }

    override suspend fun append(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        chunk: BlobChunkRecord,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        validateBlobChunk(chunk)
        return dao.append(
            scope.sessionId,
            BlobChunkEntity(
                scope.accountKey,
                partition,
                id,
                chunk.offset,
                chunk.length,
                chunk.payload,
            ),
            expectedRevision,
        )
    }

    override suspend fun complete(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        completion: String,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        validateBlobEnvelope(completion)
        return dao.complete(
            scope.sessionId,
            scope.accountKey,
            partition,
            id,
            expectedRevision,
            completion,
        )
    }

    override suspend fun updateState(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
        state: String,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        validateBlobEnvelope(state)
        return dao.updateState(
            scope.sessionId,
            scope.accountKey,
            partition,
            id,
            expectedRevision,
            state,
        )
    }

    override suspend fun chunk(
        scope: BlobScope,
        partition: String,
        id: String,
        offset: Long,
    ): BlobChunkRecord? {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobOffset(offset)
        return dao.chunk(scope.sessionId, scope.accountKey, partition, id, offset)?.let {
            BlobChunkRecord(it.offset, it.length, it.payload)
        }
    }

    override suspend fun remove(
        scope: BlobScope,
        partition: String,
        id: String,
        expectedRevision: Long,
    ): Boolean {
        validateBlobScope(scope, partition)
        validateBlobId(id)
        validateBlobRevision(expectedRevision)
        return dao.remove(scope.sessionId, scope.accountKey, partition, id, expectedRevision)
    }

    override suspend fun containsRecords() = dao.containsRecords()
}

private fun BlobEntity.toRecord() =
    BlobRecord(partition, id, size, metadata, storedBytes, completion, revision, state)
