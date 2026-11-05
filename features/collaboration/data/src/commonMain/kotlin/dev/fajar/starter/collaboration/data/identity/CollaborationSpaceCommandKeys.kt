package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.database.CommandStore
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64

/** Borrows the existing protected command key; never replaces it over retained ciphertext. */
internal class CollaborationSpaceCommandKeys(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    private val credentials: AtomicCredentialStore,
) {
    suspend fun <T> use(create: Boolean = false, block: suspend (ByteArray) -> T): T {
        val key = acquire(create)
        return try {
            block(key)
        } finally {
            key.fill(0)
        }
    }

    private suspend fun acquire(create: Boolean): ByteArray {
        val retained = credentials.read()
        if (retained != null) return decode(retained)
        check(create && !commands.containsRecords()) { "Command content key is unavailable." }
        val generated = cipher.generateKey()
        return try {
            require(generated.size == 32)
            decode(credentials.writeIfAbsent(Base64.encode(generated)))
        } finally {
            generated.fill(0)
        }
    }

    private fun decode(value: String): ByteArray {
        require(value.length in 1..64)
        return Base64.decode(value).also { require(it.size == 32) }
    }
}
