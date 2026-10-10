package dev.fajar.starter.securestorage

/**
 * Raw credential I/O. Session policy, serialization, and failure mapping belong above this port.
 */
interface CredentialStore {
    val persistent: Boolean

    suspend fun read(): String?

    suspend fun write(value: String?)

    fun close() {}
}
