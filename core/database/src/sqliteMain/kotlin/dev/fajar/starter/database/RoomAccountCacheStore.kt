package dev.fajar.starter.database

import dev.fajar.starter.database.dao.AccountCacheDao

internal class RoomAccountCacheStore(private val dao: AccountCacheDao) : AccountCacheStore {
    override suspend fun activate(sessionId: String?, accountKey: String?) {
        require(accountKey == null || (sessionId != null && accountKey.length in 1..2048))
        dao.activate(sessionId, accountKey)
    }
}
