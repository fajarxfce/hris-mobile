package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.*
import java.util.Collections
import kotlin.test.assertFalse
import org.matrix.rustcomponents.sdk.*

/** A delayed raw SDK boundary, with observable native resource ownership and no network. */
internal class PendingRuntimeClientSource :
    AndroidCollaborationClientSource by AndroidCollaborationSdkClientSource() {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var beforeCreate: suspend () -> Unit = {}
    var beforeRestore: suspend () -> Unit = {}

    private val native =
        object : Client(NoHandle) {
            override suspend fun enableAllSendQueues(enable: Boolean) {
                assertFalse(enable)
                calls += "queues.disable"
            }

            override fun encryption(): Encryption =
                object : Encryption(NoHandle) {
                    override suspend fun waitForE2eeInitializationTasks() {
                        calls += "encryption.drain"
                    }

                    override fun close() {
                        calls += "encryption.close"
                    }
                }

            override suspend fun pause() {
                calls += "native.pause"
            }

            override fun close() {
                calls += "native.close"
            }
        }

    override suspend fun create(
        directory: CollaborationSdkStoreLease,
        passphrase: String,
        initialSession: Session,
        delegate: ClientSessionDelegate,
    ): Client {
        calls += "native.create"
        beforeCreate()
        return native
    }

    override fun observeAuthentication(client: Client, delegate: ClientDelegate): TaskHandle =
        registration("authentication")

    override fun observeCustomToDevice(
        client: Client,
        eventType: String,
        listener: ToDeviceMessageListener,
    ): TaskHandle = registration("readiness")

    override suspend fun restorePaused(client: Client, initialSession: Session) {
        calls += "native.restore"
        beforeRestore()
    }

    private fun registration(name: String): TaskHandle {
        calls += "$name.observe"
        return object : TaskHandle(NoHandle) {
            override fun cancel() {
                calls += "$name.cancel"
            }

            override fun close() {
                calls += "$name.close"
            }
        }
    }
}
