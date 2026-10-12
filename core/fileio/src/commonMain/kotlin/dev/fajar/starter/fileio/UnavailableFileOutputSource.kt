package dev.fajar.starter.fileio

/** Hosts without a file UI report an explicit capability failure instead of pretending to save. */
class UnavailableFileOutputSource : FileOutputSource {
    override suspend fun select(
        requestId: String,
        fileName: String,
        mediaType: String,
        size: Long,
    ): Boolean = throw FileUnavailableException()

    override suspend fun write(requestId: String, offset: Long, bytes: ByteArray): Unit =
        throw FileReleasedException()

    override suspend fun complete(requestId: String): Unit = throw FileReleasedException()

    override suspend fun release(requestId: String) = Unit

    override fun close() = Unit
}
