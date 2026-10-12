package dev.fajar.starter.fixtures

import dev.fajar.starter.fileio.*
import kotlinx.coroutines.flow.flowOf

class DocumentInputUi(private val name: String) : FileInputSource {
    override suspend fun select(requestId: String, mediaTypes: Set<String>) =
        FileInputMetadata(name, "application/pdf", 3)

    override fun read(requestId: String) = flowOf(byteArrayOf(1, 2, 3))

    override suspend fun release(requestId: String) = Unit

    override fun close() = Unit
}
