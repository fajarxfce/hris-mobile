package dev.fajar.starter.securestorage

/**
 * Raw record creation. Returns the retained value without replacing an existing record. A competing
 * owner may receive a technical contention error; callers own recovery policy.
 */
interface AtomicCredentialStore : CredentialStore {
    suspend fun writeIfAbsent(value: String): String
}
