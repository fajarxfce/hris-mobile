@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.data.dto.SdkRoomKeyBatch
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.*

/** One native registration owns two bounded slots; observation cannot consume either slot. */
internal class SdkRoomKeyMailbox(private val receipt: () -> String = { Uuid.random().toString() }) {
    private data class Pending(
        val delivered: SdkRoomKeyBatch? = null,
        val waiting: SdkRoomKeyBatch? = null,
        val closed: Boolean = false,
    )

    private val pending = MutableStateFlow(Pending())

    /** Copies only bounded input. Invalid raw sizes retain reconciliation, never silent success. */
    fun offer(roomIds: List<String>, reconcileRequired: Boolean) {
        if (pending.value.closed) return
        var reconciliation = reconcileRequired || roomIds.size > 64
        val copied = buildList {
            for (index in 0 until minOf(roomIds.size, 64)) {
                val id = roomIds[index]
                if (id.length !in 1..1024) reconciliation = true else if (id !in this) add(id)
            }
        }
        if (copied.isEmpty() && !reconciliation) return
        val batch = SdkRoomKeyBatch(receipt(), copied, reconciliation)
        pending.update { before ->
            when {
                before.closed -> before
                before.delivered == null -> before.copy(delivered = batch)
                else -> before.copy(waiting = merge(before.waiting, batch))
            }
        }
    }

    /**
     * Stable until acknowledged. Returns a copy so a consumer cannot mutate retained callback data.
     */
    fun current(): SdkRoomKeyBatch? = pending.value.takeUnless { it.closed }?.delivered?.snapshot()

    fun observe(): Flow<SdkRoomKeyBatch> =
        pending
            .transformWhile { state ->
                if (state.closed || pending.value.closed) false
                else {
                    state.delivered?.let { emit(it.snapshot()) }
                    true
                }
            }
            .distinctUntilChanged()

    /**
     * Only the exact delivered receipt can release its slot; concurrent new callbacks stay waiting.
     */
    fun acknowledge(receiptId: String): Boolean {
        val before =
            pending.getAndUpdate { state ->
                if (state.closed || state.delivered?.receiptId != receiptId) state
                else state.copy(delivered = state.waiting, waiting = null)
            }
        return !before.closed && before.delivered?.receiptId == receiptId
    }

    /** Stops observation; this is disposal, not a durable acknowledgment of pending work. */
    fun close() {
        pending.update { Pending(closed = true) }
    }

    private fun merge(previous: SdkRoomKeyBatch?, next: SdkRoomKeyBatch): SdkRoomKeyBatch {
        if (previous == null) return next
        val union = (previous.roomIds + next.roomIds).distinct()
        return next.copy(
            roomIds = union.take(64),
            reconcileRequired =
                previous.reconcileRequired || next.reconcileRequired || union.size > 64,
        )
    }

    private fun SdkRoomKeyBatch.snapshot() = copy(roomIds = roomIds.toList())

    override fun toString() = "SdkRoomKeyMailbox([redacted])"
}
