@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.fajar.starter.collaboration.data.datasources

import kotlinx.cinterop.*
import platform.Foundation.NSLock
import platform.posix.*

/** Owns the original lock and an anchored directory descriptor. Never creates retained files. */
internal class AppleSdkDirectoryLock
private constructor(val path: String, private val directory: Int, private val guard: Int) :
    AutoCloseable {
    private val monitor = NSLock()
    private var closed = false

    fun databasePresence(): Pair<Boolean, Boolean> {
        monitor.lock()
        try {
            check(!closed) { "SDK directory is closed." }
            val database = openat(directory, "database", DIRECTORY_FLAGS)
            if (database < 0) {
                val failure = errno
                if (failure == ENOENT) return false to false
                throw AppleSdkFileException(failure)
            }
            var failure: Throwable? = null
            try {
                return retainedDatabase(database, "matrix-sdk-state.sqlite3") to
                    retainedDatabase(database, "matrix-sdk-crypto.sqlite3")
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                closeDescriptor(database, failure)
            }
        } finally {
            monitor.unlock()
        }
    }

    override fun close() {
        monitor.lock()
        try {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                // flock belongs to this open description; close releases it without an unlock race.
                closeDescriptor(guard)
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                closeDescriptor(directory, failure)
            }
        } finally {
            monitor.unlock()
        }
    }

    companion object {
        /** The caller supplies a trusted private root; only that root may resolve an OS alias. */
        fun openExisting(root: String, storeId: String): AppleSdkDirectoryLock? {
            require(root.startsWith('/') && '\u0000' !in root)
            require(storeId.matches(Regex("[a-f0-9]{64}")))
            val resolved = realpath(root, null)
            if (resolved == null) {
                val failure = errno
                if (failure == ENOENT) return null
                throw AppleSdkFileException(failure)
            }
            val base =
                try {
                    resolved.toKString()
                } finally {
                    free(resolved)
                }
            val parent = open(base, DIRECTORY_FLAGS)
            if (parent < 0) throw AppleSdkFileException(errno)
            var failure: Throwable? = null
            var acquired: AppleSdkDirectoryLock? = null
            try {
                acquired = openChild(parent, base, storeId)
                return acquired
            } catch (caught: Throwable) {
                failure = caught
                throw caught
            } finally {
                try {
                    closeDescriptor(parent, failure)
                } catch (closing: Throwable) {
                    runCatching { acquired?.close() }.exceptionOrNull()?.let(closing::addSuppressed)
                    throw closing
                }
            }
        }

        private fun openChild(parent: Int, base: String, storeId: String): AppleSdkDirectoryLock? {
            val directory = openat(parent, storeId, DIRECTORY_FLAGS)
            if (directory < 0) {
                val failure = errno
                if (failure == ENOENT) return null
                throw AppleSdkFileException(failure)
            }
            var guard = -1
            try {
                // NONBLOCK also prevents an obstructing FIFO from hanging before its type check.
                guard =
                    openat(
                        directory,
                        "owner.lock",
                        O_WRONLY or O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK,
                    )
                if (guard < 0) throw AppleSdkFileException(errno)
                memScoped {
                    val entry = alloc<stat>()
                    if (fstat(guard, entry.ptr) != 0) throw AppleSdkFileException(errno)
                    check(entry.st_mode.toInt() and S_IFMT == S_IFREG) { "SDK lock is not a file." }
                }
                if (flock(guard, LOCK_EX or LOCK_NB) != 0) {
                    val failure = errno
                    if (failure == EWOULDBLOCK || failure == EAGAIN)
                        throw CollaborationSdkStoreBusyException()
                    throw AppleSdkFileException(failure)
                }
                return AppleSdkDirectoryLock(base.trimEnd('/') + "/" + storeId, directory, guard)
            } catch (failure: Throwable) {
                if (guard >= 0) closeDescriptor(guard, failure)
                closeDescriptor(directory, failure)
                throw failure
            }
        }
    }
}

/**
 * Native errno is retained for internal classification; neither paths nor key material are printed.
 */
internal class AppleSdkFileException(val errorNumber: Int) :
    IllegalStateException("SDK filesystem operation failed.")

private const val DIRECTORY_FLAGS = O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC

private fun retainedDatabase(directory: Int, name: String): Boolean = memScoped {
    val entry = alloc<stat>()
    if (fstatat(directory, name, entry.ptr, AT_SYMLINK_NOFOLLOW) != 0) {
        val failure = errno
        if (failure == ENOENT) return@memScoped false
        throw AppleSdkFileException(failure)
    }
    check(entry.st_mode.toInt() and S_IFMT == S_IFREG) { "SDK database entry is not a file." }
    entry.st_size > 0
}

private fun closeDescriptor(descriptor: Int, primary: Throwable? = null) {
    // Never retry close: a reused descriptor must not be closed by a late cleanup attempt.
    if (close(descriptor) == 0) return
    val closing = AppleSdkFileException(errno)
    if (primary == null) throw closing else primary.addSuppressed(closing)
}
