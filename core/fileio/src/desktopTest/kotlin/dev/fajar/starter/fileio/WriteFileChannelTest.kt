package dev.fajar.starter.fileio

import dev.fajar.starter.common.result.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class WriteFileChannelTest {
    @Test
    fun nativePermissionAndExistingPathFailuresHaveDistinctCodes() = runTest {
        val denied =
            assertIs<AppResult.Failed>(
                safeFileWriteCall { throw AccessDeniedException("private path") }
            )
        assertEquals(FailureKind.Permission, denied.failure.kind)
        assertEquals("client.file_permission", denied.failure.code)
        val exists =
            assertIs<AppResult.Failed>(
                safeFileWriteCall { throw FileAlreadyExistsException("private path") }
            )
        assertEquals(FailureKind.Conflict, exists.failure.kind)
        assertEquals("client.file_exists", exists.failure.code)
        assertFalse(exists.failure.message.contains("private path"))
    }

    @Test
    fun shortNativeWritesPreserveTheBorrowedBuffer() = runTest {
        val bytes = ByteArray(FILE_OUTPUT_BLOCK_BYTES) { (it % 251).toByte() }
        val copied = mutableListOf<Byte>()
        val channel =
            object : WritableByteChannel {
                var closed = false

                override fun isOpen() = !closed

                override fun close() {
                    closed = true
                }

                override fun write(source: ByteBuffer): Int {
                    val count = minOf(8191, source.remaining())
                    repeat(count) { copied += source.get() }
                    return count
                }
            }
        channel.use { writeFileChannel(it, bytes) }
        assertFalse(channel.isOpen)
        assertContentEquals(bytes, copied.toByteArray())
        assertEquals(250.toByte(), bytes[250])
    }

    @Test
    fun zeroProgressAndNativeErrorsAreNeitherRetriedNorHidden() = runTest {
        var writes = 0
        val stalled =
            object : WritableByteChannel {
                override fun isOpen() = true

                override fun close() = Unit

                override fun write(source: ByteBuffer): Int {
                    writes++
                    return 0
                }
            }
        assertFailsWith<IOException> { writeFileChannel(stalled, byteArrayOf(1)) }
        assertEquals(1, writes)
        val original = IOException("provider failure")
        val failed =
            object : WritableByteChannel by stalled {
                override fun write(source: ByteBuffer): Int = throw original
            }
        val reported = assertFailsWith<IOException> { writeFileChannel(failed, byteArrayOf(1)) }
        assertTrue(generateSequence<Throwable>(reported) { it.cause }.any { it === original })
    }

    @Test
    fun cancellationInterruptsAPendingWriteAndClosesItsOwner() = runTest {
        val entered = CompletableDeferred<Unit>()
        val channel =
            object : WritableByteChannel {
                var closed = false

                override fun isOpen() = !closed

                override fun close() {
                    closed = true
                }

                override fun write(source: ByteBuffer): Int {
                    entered.complete(Unit)
                    Thread.sleep(Long.MAX_VALUE)
                    return 0
                }
            }
        val request = launch { channel.use { writeFileChannel(it, byteArrayOf(1)) } }
        entered.await()
        request.cancelAndJoin()
        assertTrue(channel.closed)
    }

    @Test
    fun anOutputRejectsGapsOverflowAndPrematureCompletion() = runTest {
        val file = Files.createTempFile("file-output-", ".bin")
        val channel = FileChannel.open(file, StandardOpenOption.WRITE)
        val output = JvmFileOutput(Any(), 3, channel)
        try {
            assertFailsWith<FileChangedException> { output.complete() }
            assertFailsWith<FileChangedException> { output.write(1, byteArrayOf(1)) }
            assertFailsWith<FileChangedException> { output.write(0, byteArrayOf(1, 2, 3, 4)) }
            output.write(0, byteArrayOf(1, 2))
            assertFailsWith<FileChangedException> { output.write(0, byteArrayOf(3)) }
            output.write(2, byteArrayOf(3))
            output.complete()
            output.close()
            assertFalse(channel.isOpen)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(file))
        } finally {
            output.close()
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun oneFailedCloseDoesNotLeaveOtherDescriptorsOpen() = runTest {
        val first = Files.createTempFile("file-output-", ".bin")
        val second = Files.createTempFile("file-output-", ".bin")
        val firstChannel = FileChannel.open(first, StandardOpenOption.WRITE)
        val secondChannel = FileChannel.open(second, StandardOpenOption.WRITE)
        val failure = IOException("close failure")
        val failing =
            JvmFileOutput(
                Any(),
                1,
                firstChannel,
                owner = {
                    firstChannel.close()
                    throw failure
                },
            )
        val other = JvmFileOutput(Any(), 1, secondChannel)
        try {
            assertSame(
                failure,
                assertFailsWith<IOException> { closeFileOutputs(listOf(failing, other)) },
            )
            assertFalse(firstChannel.isOpen)
            assertFalse(secondChannel.isOpen)
        } finally {
            firstChannel.close()
            secondChannel.close()
            Files.deleteIfExists(first)
            Files.deleteIfExists(second)
        }
    }
}
