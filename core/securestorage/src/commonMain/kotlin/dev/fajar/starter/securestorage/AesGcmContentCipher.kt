package dev.fajar.starter.securestorage

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses the platform provider's random nonce and full authentication tag; no custom cipher. */
class AesGcmContentCipher : ContentCipher {
    override suspend fun generateKey(): ByteArray =
        withContext(Dispatchers.Default) {
            CryptographyProvider.Default.get(AES.GCM)
                .keyGenerator(AES.Key.Size.B256)
                .generateKey()
                .encodeToByteArray(AES.Key.Format.RAW)
                .also { currentCoroutineContext().ensureActive() }
        }

    override suspend fun encrypt(
        key: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(key.size == 32 && associatedData.size in 1..4096 && plaintext.size <= MAX_PLAINTEXT)
        val ownedKey = key.copyOf()
        val context = byteArrayOf(VERSION) + associatedData
        val content = plaintext.copyOf()
        try {
            return withContext(Dispatchers.Default) {
                val secret =
                    CryptographyProvider.Default.get(AES.GCM)
                        .keyDecoder()
                        .decodeFromByteArray(AES.Key.Format.RAW, ownedKey)
                (byteArrayOf(VERSION) + secret.cipher().encrypt(content, context)).also {
                    currentCoroutineContext().ensureActive()
                }
            }
        } finally {
            ownedKey.fill(0)
            content.fill(0)
        }
    }

    override suspend fun decrypt(
        key: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(key.size == 32 && associatedData.size in 1..4096)
        require(ciphertext.size in 29..MAX_PLAINTEXT + 29 && ciphertext[0] == VERSION)
        val ownedKey = key.copyOf()
        val context = byteArrayOf(VERSION) + associatedData
        val content = ciphertext.copyOfRange(1, ciphertext.size)
        try {
            return withContext(Dispatchers.Default) {
                val secret =
                    CryptographyProvider.Default.get(AES.GCM)
                        .keyDecoder()
                        .decodeFromByteArray(AES.Key.Format.RAW, ownedKey)
                secret.cipher().decrypt(content, context).also {
                    currentCoroutineContext().ensureActive()
                }
            }
        } finally {
            ownedKey.fill(0)
        }
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val MAX_PLAINTEXT = 96_000
    }
}
