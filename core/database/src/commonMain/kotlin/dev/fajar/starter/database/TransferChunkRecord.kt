package dev.fajar.starter.database

/** A bounded binary block, addressed by its byte offset within one transfer. */
data class TransferChunkRecord(val offset: Long, val bytes: ByteArray)

const val MAX_STORED_TRANSFER_CHUNK_BYTES = 262_144
