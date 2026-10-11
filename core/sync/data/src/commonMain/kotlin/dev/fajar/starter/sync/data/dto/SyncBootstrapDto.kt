package dev.fajar.starter.sync.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class SyncBootstrapDto(
    val collections: List<String>,
    val items: List<SyncResourceDto>,
    val nextCursor: String? = null,
    val changesCursor: String? = null,
    val serverTime: String,
) {
    override fun toString() = "SyncBootstrapDto([redacted])"
}
