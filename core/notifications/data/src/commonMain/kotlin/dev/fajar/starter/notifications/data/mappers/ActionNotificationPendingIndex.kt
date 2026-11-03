package dev.fajar.starter.notifications.data.mappers

import dev.fajar.starter.notifications.data.dto.ActionNotificationReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val ACTION_NOTIFICATION_PENDING_CAPACITY = 64
internal const val ACTION_NOTIFICATION_PENDING_BYTES = 32_768

/** Device-local opaque IPC inventory. No preview, envelope, reply text or account identifier. */
internal fun encodeActionNotificationPendingIndex(
    references: List<ActionNotificationReference>
): ByteArray {
    require(references.size <= ACTION_NOTIFICATION_PENDING_CAPACITY)
    require(references.all(::validActionNotificationReference))
    require(references.map { it.id }.distinct().size == references.size)
    val value =
        PendingIndex(
            references = references.sortedBy { it.id }.map { PendingReference(it.id, it.renderId) }
        )
    val bytes = pendingJson.encodeToString(value).encodeToByteArray()
    require(bytes.size <= ACTION_NOTIFICATION_PENDING_BYTES)
    return bytes
}

internal fun decodeActionNotificationPendingIndex(
    bytes: ByteArray
): List<ActionNotificationReference> {
    require(bytes.size in 1..ACTION_NOTIFICATION_PENDING_BYTES)
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    val value = pendingJson.decodeFromString<PendingIndex>(text)
    require(value.schema == 1)
    val references = value.references.map { ActionNotificationReference(it.id, it.renderId) }
    val canonical = encodeActionNotificationPendingIndex(references)
    try {
        require(canonical.contentEquals(bytes))
    } finally {
        canonical.fill(0)
    }
    return references
}

@Serializable
private data class PendingIndex(val schema: Int = 1, val references: List<PendingReference>)

@Serializable private data class PendingReference(val id: String, val renderId: String)

private val pendingJson = Json { encodeDefaults = true }
