package dev.fajar.starter.sync.data.datasources

/** Raw platform queue I/O. Does not execute use cases or decide retry policy. */
interface WorkScheduler {
    suspend fun enqueue(key: String)
}
