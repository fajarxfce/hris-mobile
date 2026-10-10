package dev.fajar.starter.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.fajar.starter.database.entities.InboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY createdAtEpochMillis DESC, id ASC")
    fun observe(): Flow<List<InboxEntity>>

    @Upsert suspend fun upsert(record: InboxEntity)

    @Query("UPDATE inbox SET `read` = 1 WHERE id = :id") suspend fun markRead(id: String)

    @Query("DELETE FROM inbox") suspend fun clear()
}
