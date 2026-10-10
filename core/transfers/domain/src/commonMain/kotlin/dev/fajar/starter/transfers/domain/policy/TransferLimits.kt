package dev.fajar.starter.transfers.domain.policy

import dev.fajar.starter.transfers.domain.entities.TransferFile

object TransferLimits {
    const val CHUNK_BYTES = 262_144
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    const val MAX_QUEUE_BYTES = 200L * 1024 * 1024
    const val MAX_ITEMS = 20
    const val BATCH_CHUNKS = 32
}

fun validTransferFile(file: TransferFile): Boolean =
    file.size in 1..TransferLimits.MAX_FILE_BYTES &&
        file.name.length in 1..180 &&
        file.name.none { it < ' ' || it == '/' || it == '\\' } &&
        file.mediaType.length in 1..120 &&
        file.mediaType.none { it < ' ' }
