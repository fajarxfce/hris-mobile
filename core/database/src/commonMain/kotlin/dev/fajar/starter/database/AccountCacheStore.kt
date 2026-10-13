package dev.fajar.starter.database

/** Activates one session/account, clearing temporary data while retaining account-owned records. */
interface AccountCacheStore {
    suspend fun activate(sessionId: String?, accountKey: String? = null)
}
