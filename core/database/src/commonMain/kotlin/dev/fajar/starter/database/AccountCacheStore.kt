package dev.fajar.starter.database

/** Switching identity clears derived caches while retaining separately owned durable commands. */
interface AccountCacheStore {
    suspend fun activate(sessionId: String?, accountKey: String? = null)
}
