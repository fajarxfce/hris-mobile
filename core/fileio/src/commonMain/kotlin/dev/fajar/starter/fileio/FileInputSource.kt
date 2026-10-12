package dev.fajar.starter.fileio

import kotlinx.coroutines.flow.Flow

/**
 * Raw user-selected input. The caller allocates a unique request ID before selection and always
 * releases it in finally, including cancellation or failed delivery of the selection result.
 * Handles are process-local; durable consumers must copy the bytes into their own storage.
 */
interface FileInputSource {
    /** Null is an explicit picker cancellation. SDK failures remain technical exceptions. */
    suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata?

    /** Cold, sequential 64 KiB blocks, with only the last block possibly shorter. */
    fun read(requestId: String): Flow<ByteArray>

    /** Releases the selection and cancels any outstanding picker/read for that request. */
    suspend fun release(requestId: String)

    /** The platform/container owner closes all registrations and inputs. */
    fun close()
}

const val FILE_INPUT_BLOCK_BYTES = 64 * 1024
