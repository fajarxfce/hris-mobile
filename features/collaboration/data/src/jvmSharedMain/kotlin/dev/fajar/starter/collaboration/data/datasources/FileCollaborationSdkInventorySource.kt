package dev.fajar.starter.collaboration.data.datasources

import java.io.File
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

/** Raw existing-file inspection sharing the SDK owner's lock. It never opens a database. */
class FileCollaborationSdkInventorySource(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CollaborationSdkInventorySource {
    override suspend fun inspect(storeId: String): CollaborationSdkInventoryLease? {
        require(storeId.matches(Regex("[a-f0-9]{64}")))
        var acquired: CollaborationSdkInventoryLease? = null
        try {
            return withContext(io) {
                currentCoroutineContext().ensureActive()
                inspectFiles(storeId).also {
                    acquired = it
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun inspectFiles(storeId: String): CollaborationSdkInventoryLease? {
        val base = root.canonicalFile
        val baseAttributes = attributes(base) ?: return null
        check(baseAttributes.isDirectory) { "SDK root is not a directory." }
        val directory = File(base, storeId)
        require(directory.canonicalFile == directory.absoluteFile)
        val directoryAttributes = attributes(directory) ?: return null
        check(directoryAttributes.isDirectory) { "SDK path is not a directory." }
        val guard = File(directory, "owner.lock")
        require(guard.canonicalFile == guard.absoluteFile)
        check(attributes(guard)?.isRegularFile == true) { "Original SDK lock is unavailable." }
        // No CREATE: a partially missing store is never repaired by inspection.
        val channel =
            FileChannel.open(guard.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        var lock: FileLock? = null
        try {
            lock =
                try {
                    channel.tryLock() ?: throw CollaborationSdkStoreBusyException()
                } catch (_: OverlappingFileLockException) {
                    throw CollaborationSdkStoreBusyException()
                }
            val database = File(directory, "database")
            require(database.canonicalFile == database.absoluteFile)
            val databaseAttributes = attributes(database)
            if (databaseAttributes != null) {
                check(databaseAttributes.isDirectory) { "SDK database path is not a directory." }
            }
            return FileSdkInventoryLease(
                storeId,
                retainedDatabase(File(database, "matrix-sdk-state.sqlite3")),
                retainedDatabase(File(database, "matrix-sdk-crypto.sqlite3")),
                channel,
                requireNotNull(lock),
            )
        } catch (failure: Throwable) {
            runCatching { lock?.release() }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { channel.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
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

    private fun retainedDatabase(file: File): Boolean {
        require(file.canonicalFile == file.absoluteFile)
        val entry = attributes(file) ?: return false
        check(entry.isRegularFile) { "SDK database entry is not a file." }
        return entry.size() > 0
    }
}

private class FileSdkInventoryLease(
    override val storeId: String,
    override val stateDatabasePresent: Boolean,
    override val cryptoDatabasePresent: Boolean,
    private val channel: FileChannel,
    private val lock: FileLock,
) : CollaborationSdkInventoryLease {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    override fun toString() = "CollaborationSdkInventoryLease([redacted])"
}
