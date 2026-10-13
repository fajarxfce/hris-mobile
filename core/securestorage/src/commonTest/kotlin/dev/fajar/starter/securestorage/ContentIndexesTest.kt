package dev.fajar.starter.securestorage

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ContentIndexesTest {
    private val key = ByteArray(32) { it.toByte() }
    private val pending = "account-a/company-a/status/pending".encodeToByteArray()
    private val approved = "account-a/company-a/status/approved".encodeToByteArray()

    @Test
    fun tokensMatchIndependentHkdfSha256AndHmacVectorsAcrossPlatforms() = runTest {
        // Fixed vectors calculated independently with Python's RFC 5869/HMAC primitives.
        val tokens = contentIndexes(key, listOf(pending, approved))
        assertEquals(
            listOf(
                "a9f495ea1d17d98adc1f100f97b8feb04ea7a0f04626837d2ac6c7098e9c9e44",
                "0e740ab6dae985b3be0df1fba34de299de2e32b522ab5f894dfb6283644fe525",
            ),
            tokens,
        )
        assertEquals(tokens[0], contentIndexes(key, listOf(pending)).single())
        assertContentEquals(ByteArray(32) { it.toByte() }, key)
        assertEquals("account-a/company-a/status/pending", pending.decodeToString())
    }

    @Test
    fun ownerFilterAndKeyChangesCannotReuseTheSameToken() = runTest {
        val contexts =
            listOf(
                pending,
                approved,
                "account-b/company-a/status/pending".encodeToByteArray(),
                "account-a/company-b/status/pending".encodeToByteArray(),
            )
        val tokens = contentIndexes(key, contexts)
        assertEquals(4, tokens.distinct().size)
        assertNotEquals(
            tokens.first(),
            contentIndexes(key.reversedArray(), listOf(pending)).single(),
        )
    }

    @Test
    fun workIsBoundedAndCancellationPropagatesWithoutMutatingCallerBytes() = runTest {
        assertFailsWith<IllegalArgumentException> { contentIndexes(ByteArray(31), listOf(pending)) }
        assertFailsWith<IllegalArgumentException> { contentIndexes(key, emptyList()) }
        assertFailsWith<IllegalArgumentException> { contentIndexes(key, List(17) { pending }) }
        assertFailsWith<IllegalArgumentException> { contentIndexes(key, listOf(ByteArray(4097))) }
        assertFailsWith<IllegalArgumentException> { contentIndexes(key, listOf(byteArrayOf())) }
        assertFailsWith<CancellationException> {
            withContext(Job().apply { cancel() }) { contentIndexes(key, listOf(pending)) }
        }
        assertContentEquals(ByteArray(32) { it.toByte() }, key)
        assertEquals("account-a/company-a/status/pending", pending.decodeToString())
    }
}
