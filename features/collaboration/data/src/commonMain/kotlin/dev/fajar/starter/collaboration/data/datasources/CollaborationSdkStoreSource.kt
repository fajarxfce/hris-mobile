package dev.fajar.starter.collaboration.data.datasources

/** Raw exclusive directory acquisition. The repository owns credentials and SDK construction. */
interface CollaborationSdkStoreSource {
    suspend fun acquire(storeId: String): CollaborationSdkStoreLease

    /**
     * Existing lock, database pair and cache only; absence never creates a store or repairs one.
     */
    suspend fun acquireExisting(storeId: String): CollaborationSdkStoreLease?
}

/** Paths belong to this lease until close; no cleanup deletes retained files. */
interface CollaborationSdkStoreLease : AutoCloseable {
    val storeId: String
    val databasePath: String
    val cachePath: String
    val initiallyOccupied: Boolean
}

class CollaborationSdkStoreBusyException : IllegalStateException("SDK store is already owned.")
