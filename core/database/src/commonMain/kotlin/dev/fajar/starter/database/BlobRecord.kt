package dev.fajar.starter.database

import kotlinx.serialization.Serializable

/** Metadata, completion and mutable state are opaque ciphertext supplied by the data adapter. */
@Serializable
data class BlobRecord(
    val partition: String,
    val id: String,
    val size: Long,
    val metadata: String,
    val storedBytes: Long = 0,
    val completion: String? = null,
    val revision: Long = 0,
    val state: String? = null,
) {
    override fun toString() = "BlobRecord([redacted])"
}
