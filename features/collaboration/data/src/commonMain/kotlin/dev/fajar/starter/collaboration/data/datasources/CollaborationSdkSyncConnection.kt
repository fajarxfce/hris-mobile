package dev.fajar.starter.collaboration.data.datasources

/** Raw SDK ownership. Failed stop retains the handle for an explicit cleanup retry. */
internal interface CollaborationSdkSyncConnection {
    /** Idempotently cancel, await termination, then dispose the native handle. */
    suspend fun stop()
}
