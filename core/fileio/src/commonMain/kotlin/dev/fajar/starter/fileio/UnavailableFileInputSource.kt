package dev.fajar.starter.fileio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class UnavailableFileInputSource : FileInputSource {
    override suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata? =
        throw FileUnavailableException()

    override fun read(requestId: String): Flow<ByteArray> = flow {
        throw FileUnavailableException()
    }

    override suspend fun release(requestId: String) = Unit

    override fun close() = Unit
}
