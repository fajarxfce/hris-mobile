package dev.fajar.starter.collaboration.data.history

import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared content/index key format for originals and separately authenticated traversal rows. */
internal class CollaborationHistoryKeys(
    private val replicas: ReplicaStore,
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
            else Base64.decode(requireNotNull(credentials.read()))
        var content = byteArrayOf()
        var index = byteArrayOf()
        return try {
            require(key.size == 64)
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
            return Base64.decode(it)
        }
        if (replicas.containsRecords(COLLABORATION_HISTORY_PREFIX)) {
            // A different repository may have published its key and first ciphertext after our
            // initial credential read. Reuse that retained key; never create one over old records.
            return Base64.decode(checkNotNull(credentials.read()))
        }
        val content = cipher.generateKey()
        var index = byteArrayOf()
        var generated = byteArrayOf()
        return try {
            index = cipher.generateKey()
            generated = content + index
            Base64.decode(credentials.writeIfAbsent(Base64.encode(generated)))
        } finally {
            content.fill(0)
            index.fill(0)
            generated.fill(0)
        }
    }
}
