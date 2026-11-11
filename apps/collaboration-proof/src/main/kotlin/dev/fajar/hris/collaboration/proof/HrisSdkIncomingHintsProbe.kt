package dev.fajar.hris.collaboration.proof

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.RoomKeyChangeBatch
import org.matrix.rustcomponents.sdk.RoomKeyChangesListener
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TaskHandle

/** Borrows the fictional peer's existing client; owns only its bounded key subscription. */
internal class HrisSdkIncomingHintsProbe(private val roomId: String) {
    private val active = AtomicBoolean(true)
    private val overflow = AtomicBoolean(false)
    private val updates = Channel<RoomKeyChangeBatch>(32)
    private var encryption: Encryption? = null
    private var task: TaskHandle? = null
    private var finished = false
    private var syncResponses = 0
    private val observed = mutableSetOf<String>()

    suspend fun subscribe(client: Client) {
        check(encryption == null && task == null)
        val before = client.storedSyncPositionV2()
        val source = client.encryption().also { encryption = it }
        // Retain the returned native task even if the outer fixture is cancelled during
        // registration.
        withContext(NonCancellable) {
            task =
                requireNotNull(
                    source.subscribeToRoomKeyChanges(
                        object : RoomKeyChangesListener {
                            override fun onUpdate(changes: RoomKeyChangeBatch) {
                                if (
                                    active.get() &&
                                        updates
                                            .trySend(
                                                changes.copy(roomIds = changes.roomIds.toList())
                                            )
                                            .isFailure
                                )
                                    overflow.set(true)
                            }
                        }
                    )
                )
        }
        val first = withTimeout(10_000) { updates.receive() }
        check(first.reconcileRequired && first.roomIds.isEmpty())
        check(client.storedSyncPositionV2() == before)
    }

    /** Called by the existing serial fixture sync owner, before any application pump starts. */
    suspend fun sync(client: Client) {
        check(task != null && active.get())
        val previous = client.storedSyncPositionV2()
        val response = client.syncOnceV2(SyncSettingsV2(timeoutMs = 1uL, fullState = false))
        check(response.previousBatch == previous)
        check(response.nextBatch.isNotEmpty())
        check(client.storedSyncPositionV2() == response.nextBatch)
        val changes = response.timelineChanges
        check(!changes.rescanRequired && changes.rooms.size <= 64)
        check(changes.rooms.sumOf { it.eventIds.size } <= 256)
        changes.rooms.forEach { room ->
            check(room.roomId.encodeToByteArray().size in 1..1024)
            check(room.eventIds.size <= 64 && room.eventIds.distinct() == room.eventIds)
            check(room.eventIds.all { it.encodeToByteArray().size in 1..1024 })
            if (room.roomId == roomId) observed.addAll(room.eventIds)
        }
        check(observed.size <= 256)
        syncResponses++
        check(syncResponses <= 16)
    }

    suspend fun complete(events: JSONObject): JSONObject {
        check(syncResponses == 3)
        val expected = listOf("original", "peer", "firstEdit", "redaction")
        check(expected.all { events.getString(it) in observed })
        withTimeout(10_000) {
            var matched = false
            while (!matched) {
                val update = updates.receive()
                check(!update.reconcileRequired)
                check(update.roomIds.size in 1..64)
                check(update.roomIds.distinct() == update.roomIds)
                check(update.roomIds.all { it.encodeToByteArray().size in 1..1024 })
                matched = roomId in update.roomIds
            }
        }
        check(!overflow.get())
        close()
        check(finished && !overflow.get())
        return JSONObject()
            .put("syncResponses", syncResponses)
            .put("observedEventKinds", expected.size)
            .put("exactPositions", true)
            .put("boundedHints", true)
            .put("initialReconciliation", true)
            .put("registrationPositionUnchanged", true)
            .put("storedKeyObserved", true)
            .put("listenerFinished", true)
    }

    suspend fun close() {
        withContext(NonCancellable) {
            active.set(false)
            val owned = task
            try {
                if (owned != null) {
                    owned.cancel()
                    withTimeout(10_000) { while (!owned.isFinished()) delay(10) }
                    finished = true
                }
            } finally {
                owned?.close()
                task = null
                encryption?.close()
                encryption = null
                updates.cancel()
            }
        }
    }
}
