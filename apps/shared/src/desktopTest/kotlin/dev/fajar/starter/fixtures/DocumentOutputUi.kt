package dev.fajar.starter.fixtures

import dev.fajar.starter.fileio.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred

/** Test-only platform destination; the application still coordinates its real encrypted file. */
class DocumentOutputUi : FileOutputSource {
    @Volatile var selection: CompletableDeferred<Boolean>? = null
    @Volatile var active = false
    @Volatile var completedHash: String? = null
    val selections = AtomicInteger()
    val releases = AtomicInteger()
    val writes = AtomicInteger()
    val buffers = CopyOnWriteArrayList<ByteArray>()
    private var currentId: String? = null
    private var size = 0L
    private var offset = 0L
    private var digest = Sha256Digest()

    override suspend fun select(
        requestId: String,
        fileName: String,
        mediaType: String,
        size: Long,
    ): Boolean {
        assertEquals("certificate.pdf", fileName)
        assertEquals("application/pdf", mediaType)
        check(!active)
        selections.incrementAndGet()
        currentId = requestId
        this.size = size
        offset = 0
        digest = Sha256Digest()
        active = true
        return selection?.await() ?: true
    }

    override suspend fun write(requestId: String, offset: Long, bytes: ByteArray) {
        assertEquals(currentId, requestId)
        assertEquals(this.offset, offset)
        assertTrue(bytes.size in 1..FILE_OUTPUT_BLOCK_BYTES)
        writes.incrementAndGet()
        buffers += bytes
        digest.update(bytes)
        this.offset += bytes.size
    }

    override suspend fun complete(requestId: String) {
        assertEquals(currentId, requestId)
        assertEquals(size, offset)
        completedHash = digest.finishHex()
    }

    override suspend fun release(requestId: String) {
        assertEquals(currentId, requestId)
        releases.incrementAndGet()
        active = false
        currentId = null
    }

    override fun close() {
        active = false
    }
}
