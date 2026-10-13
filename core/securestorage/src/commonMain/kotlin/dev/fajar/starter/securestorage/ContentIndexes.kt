package dev.fajar.starter.securestorage

import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HKDF
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Opaque equality tokens for encrypted records. Contexts must bind the owner and filter fields.
 * Equality/frequency remain visible; tokens do not grant access or authenticate stored content.
 */
suspend fun contentIndexes(key: ByteArray, contexts: List<ByteArray>): List<String> {
    require(key.size == 32 && contexts.size in 1..16)
    require(contexts.all { it.size in 1..4096 })
    val ownedKey = key.copyOf()
    val ownedContexts = contexts.map { it.copyOf() }
    try {
        return withContext(Dispatchers.Default) {
            val provider = CryptographyProvider.Default
            val indexKey =
                provider
                    .get(HKDF)
                    .secretDerivation(
                        SHA256,
                        32.bytes,
                        salt = byteArrayOf(),
                        info = "starter/content-index/v1".encodeToByteArray(),
                    )
                    .deriveSecretToByteArray(ownedKey)
            try {
                val signer =
                    provider
                        .get(HMAC)
                        .keyDecoder(SHA256)
                        .decodeFromByteArray(HMAC.Key.Format.RAW, indexKey)
                        .signatureGenerator()
                ownedContexts
                    .map {
                        currentCoroutineContext().ensureActive()
                        signer.generateSignature(it).toHexString()
                    }
                    .also { currentCoroutineContext().ensureActive() }
            } finally {
                indexKey.fill(0)
            }
        }
    } finally {
        ownedKey.fill(0)
        ownedContexts.forEach { it.fill(0) }
    }
}
