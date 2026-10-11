package dev.fajar.starter.securestorage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit ephemeral storage for browser bearer tokens, tests, and opt-in desktop sessions. */
class MemoryCredentialStore : AtomicCredentialStore {
    override val persistent = false
    private val access = Mutex()
    private var value: String? = null

    override suspend fun read() = access.withLock { value }

    override suspend fun write(value: String?) {
        access.withLock { this.value = value }
    }

    override suspend fun writeIfAbsent(value: String): String =
        access.withLock {
            currentCoroutineContext().ensureActive()
            this.value ?: value.also { this.value = it }
        }
}
