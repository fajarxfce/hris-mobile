package dev.fajar.starter.inbox.data.encoding

import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Called inside the repository's creation mutex and storage boundary. */
suspend fun readOrCreateInboxContentKey(
    replicas: ReplicaStore,
    cipher: ContentCipher,
    credentials: CredentialStore,
): ByteArray {
    val saved = credentials.read()
    if (saved != null) return Base64.decode(saved)
    check(!replicas.containsRecords("inbox/")) { "Existing inbox data requires its content key." }
    val generated = cipher.generateKey()
    return try {
        withContext(NonCancellable) { credentials.write(Base64.encode(generated)) }
        generated.copyOf()
    } finally {
        generated.fill(0)
    }
}
