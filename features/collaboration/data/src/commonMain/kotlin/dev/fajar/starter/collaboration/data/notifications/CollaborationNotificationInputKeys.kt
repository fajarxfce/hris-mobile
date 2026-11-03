package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.database.DeviceRecordStore
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val NOTIFICATION_INPUT_NAMESPACE = "hris.notification-input.v1"

/** Device key ownership is independent of login. This helper never rotates or deletes a key. */
internal class CollaborationNotificationInputKeys(
    private val records: DeviceRecordStore,
    private val cipher: ContentCipher,
    private val credentials: AtomicCredentialStore,
) {
    private val creation = Mutex()

    suspend fun <T> use(
        create: Boolean = false,
        operation: suspend (ByteArray, ByteArray) -> T,
    ): T {
        val key =
            if (create) creation.withLock { acquire() }
            else decode(requireNotNull(credentials.read()))
        var content = byteArrayOf()
        var index = byteArrayOf()
        return try {
            content = key.copyOfRange(0, 32)
            index = key.copyOfRange(32, 64)
            operation(content, index)
        } finally {
            key.fill(0)
            content.fill(0)
            index.fill(0)
        }
    }

    private suspend fun acquire(): ByteArray {
        credentials.read()?.let {
            return decode(it)
        }
        if (records.page(NOTIFICATION_INPUT_NAMESPACE, limit = 1).isNotEmpty()) {
            // A concurrent owner may have published the key and its first record since our read.
            return decode(requireNotNull(credentials.read()))
        }
        val content = cipher.generateKey()
        var index = byteArrayOf()
        var generated = byteArrayOf()
        return try {
            index = cipher.generateKey()
            require(content.size == 32 && index.size == 32)
            generated = content + index
            decode(credentials.writeIfAbsent(Base64.encode(generated)))
        } finally {
            content.fill(0)
            index.fill(0)
            generated.fill(0)
        }
    }

    private fun decode(encoded: String): ByteArray {
        require(encoded.length == 88)
        val decoded = Base64.decode(encoded)
        try {
            require(decoded.size == 64 && Base64.encode(decoded) == encoded)
            return decoded
        } catch (error: Throwable) {
            decoded.fill(0)
            throw error
        }
    }
}
