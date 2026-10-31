package dev.fajar.starter.collaboration.data.datasources

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The platform supplies its private, backup-excluded root. No SDK or credential datasource calls.
 */
class FileCollaborationSdkStoreSource(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CollaborationSdkStoreSource {
    override suspend fun acquire(storeId: String): CollaborationSdkStoreLease {
        require(storeId.matches(Regex("[a-f0-9]{64}")))
        var acquired: CollaborationSdkStoreLease? = null
        try {
            return withContext(io) {
                currentCoroutineContext().ensureActive()
                acquireFiles(storeId).also {
                    acquired = it
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun acquireFiles(storeId: String): CollaborationSdkStoreLease {
        val base = root.canonicalFile
        requireDirectory(base)
        val directory = File(base, storeId)
        require(directory.canonicalFile == directory.absoluteFile)
        requireDirectory(directory)
        val guard = File(directory, "owner.lock")
        require(guard.canonicalFile == guard.absoluteFile)
        val channel = RandomAccessFile(guard, "rw").channel
        try {
            val lock =
                try {
                    channel.tryLock() ?: throw CollaborationSdkStoreBusyException()
                } catch (_: OverlappingFileLockException) {
                    throw CollaborationSdkStoreBusyException()
                }
            return leasedDirectories(storeId, directory, channel, lock)
        } catch (failure: Throwable) {
            runCatching { channel.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun leasedDirectories(
        storeId: String,
        directory: File,
        channel: FileChannel,
        lock: FileLock,
    ): CollaborationSdkStoreLease {
        val database = File(directory, "database")
        val cache = File(directory, "cache")
        listOf(database, cache).forEach {
            require(it.canonicalFile == it.absoluteFile)
            requireDirectory(it)
        }
        val occupied = listOf(database, cache).any { containsEntry(it) }
        return FileSdkStoreLease(storeId, database, cache, occupied, channel, lock)
    }

    private fun requireDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "SDK directory is unavailable." }
        check(directory.isDirectory) { "SDK path is not a directory." }
    }

    private fun containsEntry(directory: File): Boolean =
        java.nio.file.Files.newDirectoryStream(directory.toPath()).use { it.iterator().hasNext() }
}

private class FileSdkStoreLease(
    override val storeId: String,
    database: File,
    cache: File,
    override val initiallyOccupied: Boolean,
    private val channel: FileChannel,
    private val lock: FileLock,
) : CollaborationSdkStoreLease {
    override val databasePath = database.absolutePath
    override val cachePath = cache.absolutePath
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    override fun toString() = "CollaborationSdkStoreLease([redacted])"
}
