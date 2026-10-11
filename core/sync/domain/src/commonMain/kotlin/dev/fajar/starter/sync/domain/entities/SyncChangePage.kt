package dev.fajar.starter.sync.domain.entities

import kotlin.time.Instant

data class SyncChangePage(
    val items: List<SyncChange>,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
    val serverTime: Instant,
) {
    override fun toString() = "SyncChangePage([redacted])"
}
