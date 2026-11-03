package dev.fajar.starter.database

import dev.fajar.starter.database.dao.DeviceRecordDao
import dev.fajar.starter.database.entities.DeviceRecordEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RoomDeviceRecordStore(private val dao: DeviceRecordDao) : DeviceRecordStore {
    override suspend fun get(namespace: String, id: String): DeviceRecord? {
        validateDeviceRecordKey(namespace)
        validateDeviceRecordKey(id)
        currentCoroutineContext().ensureActive()
        return dao.get(namespace, id)?.record()
    }

    override suspend fun page(namespace: String, afterId: String?, limit: Int): List<DeviceRecord> {
        validateDeviceRecordKey(namespace)
        afterId?.let(::validateDeviceRecordKey)
        require(limit in 1..32)
        currentCoroutineContext().ensureActive()
        return dao.page(namespace, afterId, limit).map { it.record() }
    }

    override suspend fun retain(record: DeviceRecord, maxItems: Int): DeviceRecord? {
        validateDeviceRecord(record)
        require(maxItems in 1..128)
        currentCoroutineContext().ensureActive()
        return dao.retain(record.entity(), maxItems)?.record()
    }

    override suspend fun remove(record: DeviceRecord): Boolean {
        validateDeviceRecord(record)
        currentCoroutineContext().ensureActive()
        return dao.remove(record.namespace, record.id, record.generation, record.payload) == 1
    }
}

private fun DeviceRecord.entity() = DeviceRecordEntity(namespace, id, generation, payload)

private fun DeviceRecordEntity.record() = DeviceRecord(namespace, id, generation, payload)
