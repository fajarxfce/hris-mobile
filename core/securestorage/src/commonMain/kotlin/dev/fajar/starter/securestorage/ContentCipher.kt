package dev.fajar.starter.securestorage

/** Raw authenticated encryption. Callers own key storage, scope, expiry, and failure policy. */
interface ContentCipher {
    suspend fun generateKey(): ByteArray

    suspend fun encrypt(key: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray

    suspend fun decrypt(key: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray
}
