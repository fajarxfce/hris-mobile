package dev.fajar.starter.fileio

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class UnavailableFileInputSource : FileInputSource {
    override suspend fun select(requestId: String, mediaTypes: Set<String>): FileInputMetadata? =
        throw FileInputUnavailableException()

    override fun read(requestId: String): Flow<ByteArray> = flow {
        throw FileInputUnavailableException()
    }

    override suspend fun release(requestId: String) = Unit

    override fun close() = Unit
}
