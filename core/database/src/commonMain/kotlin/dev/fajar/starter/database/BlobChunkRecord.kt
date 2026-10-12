package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** One encrypted block; length describes its plaintext extent, never its envelope length. */
@Serializable
data class BlobChunkRecord(val offset: Long, val length: Int, val payload: String) {
    override fun toString() = "BlobChunkRecord([redacted])"
}
