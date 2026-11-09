package dev.fajar.starter.sync.domain

/** A feature-owned synchronization use case. Platform workers execute this port. */
interface SyncTask {
    val key: String

    suspend operator fun invoke(): SyncResult

    /** Hosts distinguish a fresh scheduled scan from delivery of an existing durable request. */
    suspend operator fun invoke(trigger: SyncTrigger): SyncResult = invoke()
}
