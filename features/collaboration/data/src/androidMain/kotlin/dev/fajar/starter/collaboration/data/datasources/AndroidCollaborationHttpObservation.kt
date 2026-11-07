package dev.fajar.starter.collaboration.data.datasources

import org.matrix.rustcomponents.sdk.Client

/**
 * Optional raw HTTP observation. Application composition supplies the adapter; the runtime lease
 * owns its coroutine. Implementations release subscriptions on cancellation and never own the
 * client.
 */
fun interface AndroidCollaborationHttpObservation {
    suspend fun observe(client: Client)
}
