package dev.fajar.starter.sync.domain.entities

import kotlin.time.Instant

data class SyncBootstrapPage(
    val collections: Set<SyncCollection>,
    val items: List<SyncReference>,
    val nextCursor: String?,
    val changesCursor: String?,
    val serverTime: Instant,
) {
    override fun toString() = "SyncBootstrapPage([redacted])"
}
