@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.fileio

import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL

class AppleFileOutputTest {
    @Test
    fun nativeOutputPreservesExtentAndClosesAfterCompletion() = runTest {
        withEmptyOutput { path, output ->
            assertFailsWith<FileChangedException> { output.write(1, byteArrayOf(1)) }
            assertFailsWith<FileChangedException> { output.complete() }
            output.write(0, byteArrayOf(1, 2))
            assertFailsWith<FileChangedException> { output.write(2, byteArrayOf(3, 4)) }
            output.write(2, byteArrayOf(3))
            output.complete()
            output.close()
            val bytes = ByteArray(3)
            val handle = FileSystem.SYSTEM.openReadOnly(path)
            try {
                assertEquals(3, handle.read(0, bytes, 0, 3))
            } finally {
                handle.close()
            }
            assertContentEquals(byteArrayOf(1, 2, 3), bytes)
            assertFailsWith<FileReleasedException> { output.write(3, byteArrayOf(4)) }
        }
    }

    @Test
    fun closingAPartialOutputDoesNotDeleteItsSelectedPath() = runTest {
        withEmptyOutput { path, output ->
            output.write(0, byteArrayOf(7))
            output.close()
            output.close()
            assertEquals(1L, FileSystem.SYSTEM.metadata(path).size)
            assertFailsWith<FileReleasedException> { output.complete() }
            assertFailsWith<FileChangedException> {
                AppleFileOutput.open(Any(), 3, NSURL.fileURLWithPath(path.toString()))
            }
        }
    }
}

private suspend fun withEmptyOutput(block: suspend (Path, AppleFileOutput) -> Unit) {
    val path = NSTemporaryDirectory().toPath() / "file-output-test-${Uuid.random()}.bin"
    FileSystem.SYSTEM.openReadWrite(path, mustCreate = true).close()
    val output = AppleFileOutput.open(Any(), 3, NSURL.fileURLWithPath(path.toString()))
    try {
        block(path, output)
    } finally {
        output.close()
        FileSystem.SYSTEM.delete(path)
    }
}
