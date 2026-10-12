package dev.fajar.starter.fileio

/**
 * Raw user-selected output. Its caller owns the request from before selection until release. Writes
 * are bounded and sequential. Cancellation may leave a partial copy at the chosen location; release
 * never deletes a user-selected path. SDK failures remain technical exceptions.
 */
interface FileOutputSource {
    /** False is explicit picker cancellation. The declaration bounds subsequent writes. */
    suspend fun select(requestId: String, fileName: String, mediaType: String, size: Long): Boolean

    /** Borrows the buffer until completion; the caller retains ownership of its bytes. */
    suspend fun write(requestId: String, offset: Long, bytes: ByteArray)

    /** Commit/flush exactly the declared extent and close the output. */
    suspend fun complete(requestId: String)

    /** Cancel any pending operation and close the selected output without deleting its path. */
    suspend fun release(requestId: String)

    fun close()
}

const val FILE_OUTPUT_BLOCK_BYTES = 1_048_576

internal fun validateFileOutput(fileName: String, mediaType: String, size: Long) {
    require(fileName.isNotBlank() && fileName.length <= 255 && fileName !in setOf(".", ".."))
    require(fileName.none { it.code in 0..31 || it.code in 127..159 || it == '/' || it == '\\' })
    validateFileMediaTypes(setOf(mediaType))
    require('*' !in mediaType && size > 0)
}
