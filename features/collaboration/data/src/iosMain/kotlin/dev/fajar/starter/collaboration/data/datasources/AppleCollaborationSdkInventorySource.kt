package dev.fajar.starter.collaboration.data.datasources

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Existing-only raw file inventory under the SDK's original exclusive directory lock. */
class AppleCollaborationSdkInventorySource(
    private val root: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CollaborationSdkInventorySource {
    override suspend fun inspect(storeId: String): CollaborationSdkInventoryLease? {
        var acquired: AppleSdkDirectoryLock? = null
        try {
            return withContext(io) {
                currentCoroutineContext().ensureActive()
                val directory =
                    AppleSdkDirectoryLock.openExisting(root, storeId) ?: return@withContext null
                acquired = directory
                val (state, crypto) = directory.databasePresence()
                currentCoroutineContext().ensureActive()
                AppleSdkInventoryLease(storeId, directory, state, crypto)
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }
}

private class AppleSdkInventoryLease(
    override val storeId: String,
    private val directory: AppleSdkDirectoryLock,
    override val stateDatabasePresent: Boolean,
    override val cryptoDatabasePresent: Boolean,
) : CollaborationSdkInventoryLease {
    override val databasePath = directory.path + "/database"

    override fun close() = directory.close()

    override fun toString() = "CollaborationSdkInventoryLease([redacted])"
}
