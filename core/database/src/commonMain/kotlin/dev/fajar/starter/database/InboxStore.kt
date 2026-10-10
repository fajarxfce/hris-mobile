package dev.fajar.starter.database

import kotlinx.coroutines.flow.Flow

/** Raw persistence contract. Implementations expose technical errors to repositories. */
interface InboxStore {
    fun observe(): Flow<List<InboxRecord>>

    suspend fun upsert(record: InboxRecord)

    suspend fun markRead(id: String)

    suspend fun clear()
}
