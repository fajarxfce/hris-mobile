package dev.fajar.starter.worker

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

internal data class WorkQueueSnapshot(val occupiedSlots: Set<Int>, val waiting: Boolean) {
    init {
        require(occupiedSlots.all { it in 0..1 })
        require(!waiting || occupiedSlots.isNotEmpty())
    }
}

/** One application owner. Callers persist a wake before enqueue; failure leaves it for recovery. */
internal class CollapsedWorkGate(keys: Set<String>, maxCallers: Int = 32) {
    init {
        require(keys.size <= 32)
        require(maxCallers in 1..32)
        require(
            keys.all { key ->
                key.length in 1..128 &&
                    key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
            }
        )
    }

    private val slots = Semaphore(maxCallers)
    private val locks = keys.associateWith { Mutex() }

    fun contains(key: String) = key in locks

    suspend fun enqueue(
        key: String,
        snapshot: suspend () -> WorkQueueSnapshot,
        enqueueVacantSlot: suspend (Int) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val lock = locks.getValue(key)
        check(slots.tryAcquire()) { "Work scheduling admission is full" }
        try {
            lock.withLock {
                val unfinished = snapshot()
                currentCoroutineContext().ensureActive()
                // An existing future start reads the wake committed before this snapshot.
                if (unfinished.waiting) return@withLock
                val vacant = (0..1).firstOrNull { it !in unfinished.occupiedSlots }
                check(vacant != null) { "Work scheduling execution is full" }
                // The platform keeps each independently named slot if a competing owner filled it.
                enqueueVacantSlot(vacant)
                currentCoroutineContext().ensureActive()
            }
        } finally {
            slots.release()
        }
    }
}
