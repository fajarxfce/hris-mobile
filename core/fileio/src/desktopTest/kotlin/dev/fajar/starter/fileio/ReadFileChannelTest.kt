package dev.fajar.starter.fileio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ReadFileChannelTest {
    @Test
    fun shortNativeReadsProduceBoundedIndependentBlocksAndCloseOnEarlyStop() = runTest {
        val bytes = ByteArray(FILE_INPUT_BLOCK_BYTES * 2 + 71) { (it % 251).toByte() }
        val source = TestChannel(bytes, 107)
        val chunks = source.use { readFileChannel(it).toList() }
        assertFalse(source.isOpen)
        assertEquals(
            listOf(FILE_INPUT_BLOCK_BYTES, FILE_INPUT_BLOCK_BYTES, 71),
            chunks.map { it.size },
        )
        assertContentEquals(bytes, chunks.flatMap { it.toList() }.toByteArray())
        val early = TestChannel(bytes, 1024)
        early.use { readFileChannel(it).take(1).collect() }
        assertFalse(early.isOpen)
        assertEquals(FILE_INPUT_BLOCK_BYTES, early.offset)
    }

    @Test
    fun zeroProgressAndTechnicalFailuresAreNotRetriedOrHidden() = runTest {
        val stalled = TestChannel(byteArrayOf(1), 0)
        assertFailsWith<IOException> { stalled.use { readFileChannel(it).collect() } }
        assertEquals(1, stalled.reads)
        assertFalse(stalled.isOpen)
        val failure = IOException("provider failure")
        val broken =
            object : ReadableByteChannel {
                var closed = false

                override fun isOpen() = !closed

                override fun close() {
                    closed = true
                }

                override fun read(destination: ByteBuffer): Int = throw failure
            }
        val reported = assertFailsWith<IOException> { broken.use { readFileChannel(it).collect() } }
        // Coroutine stack recovery may copy IOException; the original remains in its cause chain.
        assertTrue(generateSequence<Throwable>(reported) { it.cause }.any { it === failure })
        assertTrue(broken.closed)
    }

    @Test
    fun cancellingAPendingNativeReadInterruptsItAndClosesTheHandle() = runTest {
        val started = CompletableDeferred<Unit>()
        val channel =
            object : ReadableByteChannel {
                var closed = false

                override fun isOpen() = !closed

                override fun close() {
                    closed = true
                }

                override fun read(destination: ByteBuffer): Int {
                    started.complete(Unit)
                    Thread.sleep(Long.MAX_VALUE)
                    return -1
                }
            }
        val reader = launch { channel.use { readFileChannel(it).collect() } }
        started.await()
        reader.cancelAndJoin()
        assertTrue(reader.isCancelled)
        assertTrue(channel.closed)
    }

    private class TestChannel(private val bytes: ByteArray, private val maximumRead: Int) :
        ReadableByteChannel {
        var offset = 0
        var reads = 0
        private var closed = false

        override fun isOpen() = !closed

        override fun close() {
            closed = true
        }

        override fun read(destination: ByteBuffer): Int {
            check(!closed)
            reads++
            if (offset == bytes.size) return -1
            val count = minOf(maximumRead, destination.remaining(), bytes.size - offset)
            destination.put(bytes, offset, count)
            offset += count
            return count
        }
    }
}
