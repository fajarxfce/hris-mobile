package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The platform provisions the private backup-excluded root; only acquire may create its children.
 */
class AppleCollaborationSdkStoreSource(
    private val root: String,
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
        var acquired: AppleSdkDirectoryLock? = null
        try {
            return withContext(io) {
                currentCoroutineContext().ensureActive()
                val directory =
                    if (existingOnly) AppleSdkDirectoryLock.openExisting(root, storeId)
                    else AppleSdkDirectoryLock.openForCreation(root, storeId)
                if (directory == null) return@withContext null
                acquired = directory
                val paths = directory.storeDirectories(existingOnly)
                currentCoroutineContext().ensureActive()
                AppleSdkStoreLease(storeId, directory, paths)
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}

private class AppleSdkStoreLease(
    override val storeId: String,
    private val directory: AppleSdkDirectoryLock,
    paths: AppleSdkStoreDirectories,
) : CollaborationSdkStoreLease {
    override val databasePath = paths.databasePath
    override val cachePath = paths.cachePath
    override val initiallyOccupied = paths.occupied

    override fun close() = directory.close()

    override fun toString() = "CollaborationSdkStoreLease([redacted])"
}
