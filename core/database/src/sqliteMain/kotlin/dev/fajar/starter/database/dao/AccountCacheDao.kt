package dev.fajar.starter.database.dao

import androidx.room.*
import dev.fajar.starter.database.entities.AccountScopeEntity

@Dao
abstract class AccountCacheDao {
    @Query("SELECT * FROM account_scope WHERE id = 1")
    protected abstract suspend fun current(): AccountScopeEntity?

    @Upsert protected abstract suspend fun write(scope: AccountScopeEntity)

    @Query("DELETE FROM dashboard") protected abstract suspend fun clearDashboard()

    @Query("DELETE FROM dashboard_activity") protected abstract suspend fun clearActivity()

    @Query("DELETE FROM activity_preferences") protected abstract suspend fun clearPreferences()

    @Query("DELETE FROM dashboard_outbox") protected abstract suspend fun clearOutbox()

    @Query("DELETE FROM inbox") protected abstract suspend fun clearInbox()

    @Query("DELETE FROM transfers") protected abstract suspend fun clearTransfers()

    @Query("DELETE FROM replica_heads WHERE ownerKey = ''")
    protected abstract suspend fun clearReplicaHeads()

    @Query("DELETE FROM replica_generations WHERE ownerKey = ''")
    protected abstract suspend fun clearReplicaGenerations()

    @Transaction
    open suspend fun activate(sessionId: String?, accountKey: String?) {
        val previous = current()
        if (
            previous != null && previous.sessionId == sessionId && previous.accountKey == accountKey
        )
            return
        clearDashboard()
        clearActivity()
        clearPreferences()
        clearOutbox()
        clearInbox()
        clearTransfers()
        clearReplicaHeads()
        clearReplicaGenerations()
        write(AccountScopeEntity(sessionId = sessionId, accountKey = accountKey))
    }
}
