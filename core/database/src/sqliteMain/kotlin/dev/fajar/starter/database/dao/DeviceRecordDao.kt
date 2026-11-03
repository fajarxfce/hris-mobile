package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.entities.DeviceRecordEntity

@Dao
abstract class DeviceRecordDao {
    @Query("SELECT * FROM device_records WHERE namespace = :namespace AND id = :id")
    abstract suspend fun get(namespace: String, id: String): DeviceRecordEntity?

    @Query(
        "SELECT * FROM device_records WHERE namespace = :namespace AND (:afterId IS NULL OR id > :afterId) ORDER BY id ASC LIMIT :limit"
    )
    abstract suspend fun page(
        namespace: String,
        afterId: String?,
        limit: Int,
    ): List<DeviceRecordEntity>

    @Query("SELECT COUNT(*) FROM device_records") protected abstract suspend fun count(): Int

    @Insert protected abstract suspend fun insert(record: DeviceRecordEntity)

    @Transaction
    open suspend fun retain(record: DeviceRecordEntity, maxItems: Int): DeviceRecordEntity? {
        get(record.namespace, record.id)?.let {
            return it
        }
        if (count() >= maxItems) return null
        insert(record)
        return record
    }

    @Query(
        "DELETE FROM device_records WHERE namespace = :namespace AND id = :id AND generation = :generation AND payload = :payload"
    )
    abstract suspend fun remove(
        namespace: String,
        id: String,
        generation: String,
        payload: String,
    ): Int
}
