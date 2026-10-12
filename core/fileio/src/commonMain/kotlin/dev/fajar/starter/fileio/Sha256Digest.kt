package dev.fajar.starter.fileio

import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

/** One import owns this incremental digest. No platform buffers an entire file to hash it. */
class Sha256Digest {
    private val sink = HashingSink.sha256(blackholeSink())
    private var finished = false

    fun update(bytes: ByteArray) {
        check(!finished)
        val buffer = Buffer().write(bytes)
        sink.write(buffer, bytes.size.toLong())
    }

    fun finishHex(): String {
        check(!finished)
        finished = true
        sink.close()
        return sink.hash.hex()
    }
}
