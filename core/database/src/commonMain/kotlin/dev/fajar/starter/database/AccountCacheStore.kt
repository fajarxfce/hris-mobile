package dev.fajar.starter.database

/** Switching the active session atomically clears account-owned cached rows and pending writes. */
interface AccountCacheStore {
    suspend fun activate(sessionId: String?)
}
