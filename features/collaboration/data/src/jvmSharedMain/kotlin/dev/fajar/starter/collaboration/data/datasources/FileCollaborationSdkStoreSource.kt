package dev.fajar.starter.collaboration.data.datasources

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
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
    override suspend fun acquire(storeId: String): CollaborationSdkStoreLease =
        requireNotNull(acquire(storeId, existingOnly = false))

    override suspend fun acquireExisting(storeId: String): CollaborationSdkStoreLease? =
        acquire(storeId, existingOnly = true)

    private suspend fun acquire(
        storeId: String,
        existingOnly: Boolean,
    ): CollaborationSdkStoreLease? {
        require(storeId.matches(Regex("[a-f0-9]{64}")))
        var acquired: CollaborationSdkStoreLease? = null
        try {
            return withContext(io) {
                currentCoroutineContext().ensureActive()
                acquireFiles(storeId, existingOnly).also {
                    acquired = it
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun acquireFiles(storeId: String, existingOnly: Boolean): CollaborationSdkStoreLease? {
        val base = root.canonicalFile
        if (existingOnly && attributes(base) == null) return null
        requireDirectory(base, existingOnly)
        val directory = File(base, storeId)
        require(directory.canonicalFile == directory.absoluteFile)
        if (existingOnly && attributes(directory) == null) return null
        requireDirectory(directory, existingOnly)
        val guard = File(directory, "owner.lock")
        require(guard.canonicalFile == guard.absoluteFile)
        val channel =
            if (existingOnly) {
                check(attributes(guard)?.isRegularFile == true) {
                    "Original SDK lock is unavailable."
                }
                FileChannel.open(
                    guard.toPath(),
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } else RandomAccessFile(guard, "rw").channel
        try {
            val lock =
                try {
                    channel.tryLock() ?: throw CollaborationSdkStoreBusyException()
                } catch (_: OverlappingFileLockException) {
                    throw CollaborationSdkStoreBusyException()
                }
            return leasedDirectories(storeId, directory, channel, lock, existingOnly)
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
        existingOnly: Boolean,
    ): CollaborationSdkStoreLease {
        val database = File(directory, "database")
        val cache = File(directory, "cache")
        listOf(database, cache).forEach {
            require(it.canonicalFile == it.absoluteFile)
            requireDirectory(it, existingOnly)
        }
        if (existingOnly) {
            listOf("matrix-sdk-state.sqlite3", "matrix-sdk-crypto.sqlite3").forEach { name ->
                val file = File(database, name)
                require(file.canonicalFile == file.absoluteFile)
                val entry = attributes(file)
                check(entry?.isRegularFile == true && entry.size() > 0) {
                    "Original SDK database is unavailable."
                }
            }
        }
        val occupied = listOf(database, cache).any { containsEntry(it) }
        return FileSdkStoreLease(storeId, database, cache, occupied, channel, lock)
    }

    private fun requireDirectory(directory: File, existingOnly: Boolean) {
        if (existingOnly) {
            check(attributes(directory)?.isDirectory == true) {
                "Original SDK directory is unavailable."
            }
            return
        }
        check(directory.isDirectory || directory.mkdirs()) { "SDK directory is unavailable." }
        check(directory.isDirectory) { "SDK path is not a directory." }
    }

    private fun attributes(file: File): BasicFileAttributes? =
        try {
            Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            null
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
