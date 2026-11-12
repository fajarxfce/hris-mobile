package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import dev.fajar.starter.collaboration.data.sdk.SdkRoomKeyMailbox
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.RoomKeyChangeBatch
import org.matrix.rustcomponents.sdk.RoomKeyChangesListener
import org.matrix.rustcomponents.sdk.TaskHandle

/** Borrows one paused/restored client; owns its permanent key listener and Encryption handle. */
internal class AndroidCollaborationRoomKeySource(
    private val client: Client,
    private val mailbox: SdkRoomKeyMailbox = SdkRoomKeyMailbox(),
) : CollaborationSdkRoomKeySource, RoomKeyChangesListener {
    private val mutation = Mutex()
    private val closed = AtomicBoolean()
    private var attempted = false
    private var encryption: Encryption? = null
    private var task: TaskHandle? = null

    override suspend fun start(): Boolean {
        currentCoroutineContext().ensureActive()
        return mutation.withLock {
            if (closed.get()) return@withLock false
            if (attempted) return@withLock task != null
            attempted = true
            // Assignment belongs inside the protected acquisition. A cancelled waiter cannot
            // discard a handle returned late; the retained source remains responsible for stop.
            withContext(NonCancellable) {
                val acquired = client.encryption()
                encryption = acquired
                task = acquired.subscribeToRoomKeyChanges(this@AndroidCollaborationRoomKeySource)
            }
            currentCoroutineContext().ensureActive()
            task != null && !closed.get()
        }
    }

    override fun onUpdate(changes: RoomKeyChangeBatch) {
        if (closed.get()) return
        try {
            mailbox.offer(changes.roomIds, changes.reconcileRequired)
        } catch (_: Exception) {
            // A malformed mutable callback cannot unwind through FFI or silently lose work.
            mailbox.offer(emptyList(), true)
        }
    }

    override fun current(): SdkRoomKeyBatch? = if (closed.get()) null else mailbox.current()

    override fun observe(): Flow<SdkRoomKeyBatch> = mailbox.observe().takeWhile { !closed.get() }

    override fun acknowledge(receiptId: String): Boolean =
        !closed.get() && mailbox.acknowledge(receiptId)

    override fun deactivate() {
        closed.set(true)
        mailbox.close()
    }

    override suspend fun stop() {
        deactivate()
        mutation.withLock {
            val retained = task
            if (retained != null) {
                retained.cancel()
                checkNotNull(
                    withTimeoutOrNull(5_000) {
                        while (!retained.isFinished()) delay(10)
                        Unit
                    }
                ) {
                    "Native room-key observation is still stopping."
                }
                retained.close()
                task = null
            }
            encryption?.close()
            encryption = null
        }
    }

    override fun toString() = "AndroidCollaborationRoomKeySource([redacted])"
}
