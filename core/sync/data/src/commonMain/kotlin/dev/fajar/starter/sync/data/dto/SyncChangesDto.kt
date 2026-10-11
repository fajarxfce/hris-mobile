package dev.fajar.starter.sync.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncChangesDto(
    val items: List<SyncChangeDto>,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
    val serverTime: String,
) {
    override fun toString() = "SyncChangesDto([redacted])"
}
