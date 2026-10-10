package dev.fajar.starter.database

import dev.fajar.starter.database.dao.TransferDao
import dev.fajar.starter.database.entities.*
import kotlinx.coroutines.flow.map

internal class RoomTransferStore(private val dao: TransferDao) : TransferStore {
    override fun observe(sessionId: String) =
        dao.observe(sessionId).map { rows -> rows.map { it.toRecord() } }

    override suspend fun get(sessionId: String, id: String) = dao.get(sessionId, id)?.toRecord()

    override suspend fun create(
        sessionId: String,
        record: TransferRecord,
        maxBytes: Long,
        maxItems: Int,
    ) = dao.create(sessionId, record.toEntity(), maxBytes, maxItems)

    override suspend fun replace(
        sessionId: String,
        expectedVersion: Long,
        record: TransferRecord,
        chunk: TransferChunkRecord?,
        clearContent: Boolean,
    ) =
        dao.replace(
            sessionId,
            expectedVersion,
            record.toEntity(),
            chunk?.let { TransferChunkEntity(record.id, it.offset, it.bytes) },
            clearContent,
        )

    override suspend fun chunk(sessionId: String, id: String, offset: Long) =
        dao.chunk(sessionId, id, offset)?.let { TransferChunkRecord(it.offset, it.bytes) }

    override suspend fun remove(sessionId: String, id: String, expectedVersion: Long) =
        dao.remove(sessionId, id, expectedVersion)
}

private fun TransferEntity.toRecord() =
    TransferRecord(
        id,
        direction,
        name,
        mediaType,
        size,
        createdAtEpochMillis,
        resourceId,
        resourceVersion,
        offset,
        storedBytes,
        status,
        version,
        failureKind,
    )

private fun TransferRecord.toEntity() =
    TransferEntity(
        id,
        direction,
        name,
        mediaType,
        size,
        createdAtEpochMillis,
        resourceId,
        resourceVersion,
        offset,
        storedBytes,
        status,
        version,
        failureKind,
    )
