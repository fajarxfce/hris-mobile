package dev.fajar.starter.collaboration.data.repositories

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The pinned SDK retains focused caches after timeline disposal. Bound distinct keys for the entire
 * client lifetime, including uncertain/failed creation. Only closing that client releases the
 * budget; disposing a view cannot prove its native cache was removed.
 */
internal class SdkHistoryFocuses(private val capacity: Int = 16) {
    private val mutation = Mutex()
    private val retained = mutableSetOf<Pair<String, String>>()

    init {
        require(capacity in 1..16)
    }

    suspend fun reserve(roomId: String, anchorEventId: String): Boolean =
        mutation.withLock {
            val key = roomId to anchorEventId
            if (key in retained) return@withLock true
            if (retained.size >= capacity) return@withLock false
            retained.add(key)
        }

    override fun toString() = "SdkHistoryFocuses([redacted])"
}
