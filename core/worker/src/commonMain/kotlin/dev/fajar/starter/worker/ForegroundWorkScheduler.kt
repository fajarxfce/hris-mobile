package dev.fajar.starter.worker

import dev.fajar.starter.sync.data.datasources.WorkScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** In-process wake-ups only. A request during execution remains queued for the next run. */
class ForegroundWorkScheduler(keys: Set<String>) : WorkScheduler {
    private val requests = keys.associateWith { Channel<Unit>(Channel.CONFLATED) }

    override suspend fun enqueue(key: String) {
        requests.getValue(key).send(Unit)
    }

    internal fun requests(key: String): ReceiveChannel<Unit> = requests.getValue(key)

    fun close() {
        requests.values.forEach { it.close() }
    }
}
