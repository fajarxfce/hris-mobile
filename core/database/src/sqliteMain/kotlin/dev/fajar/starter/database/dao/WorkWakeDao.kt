package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.entities.WorkWakeEntity

@Dao
abstract class WorkWakeDao {
    @Query("SELECT * FROM work_wakes WHERE `key` = :key")
    abstract suspend fun get(key: String): WorkWakeEntity?

    @Query("SELECT COUNT(*) FROM work_wakes") protected abstract suspend fun count(): Int

    @Upsert protected abstract suspend fun put(value: WorkWakeEntity)

    @Transaction
    open suspend fun request(key: String, generation: String, maxKeys: Int): WorkWakeEntity? {
        val current = get(key)
        if (current?.generation == generation) return current
        if (current == null && count() >= maxKeys) return null
        val value = WorkWakeEntity(key, generation, true)
        put(value)
        return value
    }

    @Query(
        "UPDATE work_wakes SET pending = 0 WHERE `key` = :key AND generation = :generation AND pending = 1"
    )
    abstract suspend fun acknowledge(key: String, generation: String): Int
}
