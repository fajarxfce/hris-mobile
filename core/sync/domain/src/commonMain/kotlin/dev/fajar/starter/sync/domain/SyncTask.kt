package dev.fajar.starter.sync.domain

/** A feature-owned synchronization use case. Platform workers execute this port. */
interface SyncTask {
    val key: String

    suspend operator fun invoke(): SyncResult
}
