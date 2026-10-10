package dev.fajar.starter.database

import dev.fajar.starter.database.entities.InboxEntity
import kotlinx.coroutines.flow.map

internal class RoomInboxStore(private val dao: dev.fajar.starter.database.dao.InboxDao) :
    InboxStore {

    override fun observe() =
        dao.observe().map { rows ->
            rows.map {
                InboxRecord(
                    it.id,
                    it.title,
                    it.body,
                    it.destination,
                    it.createdAtEpochMillis,
                    it.read,
                )
            }
        }

    override suspend fun upsert(record: InboxRecord) {
        dao.upsert(
            InboxEntity(
                record.id,
                record.title,
                record.body,
                record.destination,
                record.createdAtEpochMillis,
                record.read,
            )
        )
    }

    override suspend fun markRead(id: String) = dao.markRead(id)

    override suspend fun clear() = dao.clear()
}
