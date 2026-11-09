package dev.fajar.starter.collaboration.data.datasources

import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceMessageListener

/** Raw native SDK operations; the repository owns admission, publication and resource lifetime. */
interface AndroidCollaborationClientSource {
    fun observeCustomToDevice(
        client: Client,
        eventType: String,
        listener: ToDeviceMessageListener,
    ): TaskHandle

    fun observeAuthentication(client: Client, delegate: ClientDelegate): TaskHandle?

    suspend fun create(
        directory: CollaborationSdkStoreLease,
        passphrase: String,
        initialSession: Session,
        delegate: ClientSessionDelegate,
    ): Client

    suspend fun restorePaused(client: Client, initialSession: Session)

    suspend fun identity(client: Client): CollaborationSdkIdentityData
}
