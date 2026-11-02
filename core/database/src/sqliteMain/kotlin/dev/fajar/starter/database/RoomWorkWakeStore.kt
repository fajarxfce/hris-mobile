package dev.fajar.starter.database

import dev.fajar.starter.database.dao.WorkWakeDao
import dev.fajar.starter.database.entities.WorkWakeEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RoomWorkWakeStore(private val dao: WorkWakeDao) : WorkWakeStore {
    override suspend fun get(key: String): WorkWakeRecord? {
        validateWorkWakeKey(key)
        currentCoroutineContext().ensureActive()
        return dao.get(key)?.record()
    }

    override suspend fun request(key: String, generation: String, maxKeys: Int): WorkWakeRecord? {
        validateWorkWakeKey(key)
        validateWorkWakeGeneration(generation)
        require(maxKeys in 1..128)
        currentCoroutineContext().ensureActive()
        return dao.request(key, generation, maxKeys)?.record()
    }

    override suspend fun acknowledge(key: String, generation: String): Boolean {
        validateWorkWakeKey(key)
        validateWorkWakeGeneration(generation)
        currentCoroutineContext().ensureActive()
        return dao.acknowledge(key, generation) == 1
    }
}

private fun WorkWakeEntity.record() = WorkWakeRecord(key, generation, pending)
