package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.SdkSyncProblem
import kotlinx.coroutines.flow.StateFlow

/** Raw SDK ownership. Failed stop retains the handle for an explicit cleanup retry. */
internal interface CollaborationSdkSyncConnection {
    val problems: StateFlow<SdkSyncProblem?>

    /** Idempotent while running; explicit retry may resume an exhausted transient interruption. */
    suspend fun start(): SdkSyncProblem?

    /** Stop scheduling, await the owned in-flight call, then release the connection. */
    suspend fun stop()
}
