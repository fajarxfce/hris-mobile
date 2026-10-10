package dev.fajar.starter.database

import dev.fajar.starter.database.dao.AccountCacheDao

internal class RoomAccountCacheStore(private val dao: AccountCacheDao) : AccountCacheStore {
    override suspend fun activate(sessionId: String?) = dao.activate(sessionId)
}
