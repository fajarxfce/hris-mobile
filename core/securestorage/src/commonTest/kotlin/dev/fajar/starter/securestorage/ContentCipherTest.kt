package dev.fajar.starter.securestorage

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.storage.safeStorageCall
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ContentCipherTest {
    private val cipher = AesGcmContentCipher()
    private val context = "account/company/generation/inbox/resource/7".encodeToByteArray()

    @Test
    fun authenticationFailureDoesNotEscapeTheRepositoryStorageBoundary() = runTest {
        val key = cipher.generateKey()
        val encrypted = cipher.encrypt(key, context, "private fixture".encodeToByteArray())
        val result =
            safeStorageCall(onException = {}) {
                cipher.decrypt(key, "wrong-scope".encodeToByteArray(), encrypted)
            }
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
        assertFalse(result.failure.message.contains("private fixture"))
    }

    @Test
    fun roundTripBindsTheResourceContextAndUsesFreshNonces() = runTest {
        val key = cipher.generateKey()
        val plaintext = "Confidential fixture".encodeToByteArray()
        val first = cipher.encrypt(key, context, plaintext)
        val second = cipher.encrypt(key, context, plaintext)
        assertEquals(32, key.size)
        assertFalse(first.contentEquals(second))
        assertContentEquals(plaintext, cipher.decrypt(key, context, first))
        assertContentEquals(plaintext, cipher.decrypt(key, context, second))
        assertFails { cipher.decrypt(key, "another-company".encodeToByteArray(), first) }
        assertFails { cipher.decrypt(cipher.generateKey(), context, first) }
    }

    @Test
    fun modifiedNoncePayloadTagAndFormatNeverReturnPlaintext() = runTest {
        val key = cipher.generateKey()
        val sealed = cipher.encrypt(key, context, "private fixture".encodeToByteArray())
        for (position in listOf(0, 1, 13, sealed.lastIndex)) {
            val changed = sealed.copyOf()
            changed[position] = (changed[position].toInt() xor 1).toByte()
            assertFails { cipher.decrypt(key, context, changed) }
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.decrypt(key, context, sealed.copyOf(28))
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encrypt(key, byteArrayOf(), byteArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            cipher.encrypt(key, context, ByteArray(96_001))
        }
    }

    @Test
    fun emptyAndMaximumPayloadsRetainTheirExactBytes() = runTest {
        val key = cipher.generateKey()
        for (payload in listOf(byteArrayOf(), ByteArray(96_000) { (it % 251).toByte() })) {
            assertContentEquals(
                payload,
                cipher.decrypt(key, context, cipher.encrypt(key, context, payload)),
            )
        }
    }

    @Test
    fun anAlreadyCancelledCallerCannotGenerateOrReleaseContent() = runTest {
        val key = cipher.generateKey()
        val sealed = cipher.encrypt(key, context, "private fixture".encodeToByteArray())
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> { withContext(cancelled) { cipher.generateKey() } }
        assertFailsWith<CancellationException> {
            withContext(cancelled) { cipher.decrypt(key, context, sealed) }
        }
    }
}
