package dev.fajar.starter.documents.data.replica.encoding

import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Called under the repository's key-creation lock; never replaces a lost retained key. */
suspend fun readOrCreateDocumentCatalogReplicaKey(
    replicas: ReplicaStore,
    cipher: ContentCipher,
    credentials: CredentialStore,
): ByteArray {
    val saved = credentials.read()
    if (saved != null) return Base64.decode(saved)
    check(
        !replicas.containsRecords("document-catalog/") &&
            !replicas.containsRecords("document-catalog-access/")
    ) {
        "Existing replica requires its content key."
    }
    val generated = cipher.generateKey()
    try {
        withContext(NonCancellable) { credentials.write(Base64.encode(generated)) }
        return generated.copyOf()
    } finally {
        generated.fill(0)
    }
}
