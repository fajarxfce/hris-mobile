package dev.fajar.starter.securestorage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit ephemeral storage for browser bearer tokens, tests, and opt-in desktop sessions. */
class MemoryCredentialStore : CredentialStore {
    override val persistent = false
    private val access = Mutex()
    private var value: String? = null

    override suspend fun read() = access.withLock { value }

    override suspend fun write(value: String?) {
        access.withLock { this.value = value }
    }
}
