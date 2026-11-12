package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationRoomKeySource
import dev.fajar.starter.collaboration.data.sdk.SdkRoomKeyMailbox
import org.matrix.rustcomponents.sdk.*

/** Protocol-only SDK handles: no JNI, transport, credentials or real native lifecycle proof. */
internal class NativeRoomKeySourceFixture {
    val calls = mutableListOf<String>()
    var hasHandle = true
    var finished = true
    var cancelFails = false
    var queryFails = false
    var taskCloseFails = false
    var encryptionFails = false
    var subscriptionFailure: Exception? = null
    var beforeSubscribe: suspend () -> Unit = {}
    var onCancel: () -> Unit = {}
    var listener: RoomKeyChangesListener? = null
    val task =
        object : TaskHandle(NoHandle) {
            override fun cancel() {
                calls += "task.cancel"
                onCancel()
                check(!cancelFails) { "Fixture cancel failure" }
            }

            override fun isFinished(): Boolean {
                calls += "task.finished"
                check(!queryFails) { "Fixture finished-query failure" }
                return finished
            }

            override fun close() {
                calls += "task.close"
                check(!taskCloseFails) { "Fixture task-close failure" }
            }
        }
    val encryption =
        object : Encryption(NoHandle) {
            override suspend fun subscribeToRoomKeyChanges(
                listener: RoomKeyChangesListener
            ): TaskHandle? {
                calls += "subscribe"
                this@NativeRoomKeySourceFixture.listener = listener
                beforeSubscribe()
                subscriptionFailure?.let { throw it }
                return task.takeIf { hasHandle }
            }

            override fun close() {
                calls += "encryption.close"
            }
        }
    val client =
        object : Client(NoHandle) {
            override fun encryption(): Encryption {
                calls += "encryption.acquire"
                check(!encryptionFails) { "Fixture encryption acquisition failure" }
                return this@NativeRoomKeySourceFixture.encryption
            }

            override fun close() {
                error("A borrowed native client must not be closed by the source")
            }
        }
    private var sequence = 0
    val source =
        AndroidCollaborationRoomKeySource(client, SdkRoomKeyMailbox { "receipt-${++sequence}" })

    fun emit(ids: List<String>, reconcile: Boolean = false): RoomKeyChangeBatch =
        RoomKeyChangeBatch(ids, reconcile).also { checkNotNull(listener).onUpdate(it) }
}
