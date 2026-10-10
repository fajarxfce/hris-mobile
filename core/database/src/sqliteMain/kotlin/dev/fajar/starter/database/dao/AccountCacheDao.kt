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

    @Transaction
    open suspend fun activate(sessionId: String?) {
        val previous = current()
        if (previous != null && previous.sessionId == sessionId) return
        clearDashboard()
        clearActivity()
        clearPreferences()
        clearOutbox()
        clearInbox()
        clearTransfers()
        write(AccountScopeEntity(sessionId = sessionId))
    }
}
