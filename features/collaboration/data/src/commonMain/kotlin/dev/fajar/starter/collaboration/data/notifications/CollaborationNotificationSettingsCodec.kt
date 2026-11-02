package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.data.history.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun notificationSettingsPartition(
    access: CollaborationLocalAccess,
    conversationId: String,
): String {
    historyScope(access)
    require(validCollaborationMessageId(conversationId))
    return COLLABORATION_HISTORY_PREFIX + "notifications/" + access.companyId + "/" + conversationId
}

@Serializable
private data class NotificationSettingsDto(
    val operationId: String,
    val mute: String,
    val until: Long?,
    val preview: String,
    val schema: Int = 1,
)

internal class CollaborationNotificationSettingsCodec(
    private val access: CollaborationLocalAccess,
    private val conversationId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun open(raw: ReplicaCheckpoint): CollaborationNotificationSettingsState {
        require(
            raw.published &&
                raw.handle.scope == historyScope(access) &&
                raw.handle.partition == notificationSettingsPartition(access, conversationId)
        )
        val version = CollaborationNotificationSettingsRevision(raw.handle.generation, raw.revision)
        require(validCollaborationNotificationRevision(version) && raw.payload.length in 1..8192)
        val plain = cipher.decrypt(key, aad(version), Base64.decode(raw.payload))
        return try {
            require(plain.size <= 4096)
            val dto =
                Json.decodeFromString<NotificationSettingsDto>(
                    plain.decodeToString(throwOnInvalidSequence = true)
                )
            require(dto.schema == 1)
            val mute =
                when (dto.mute) {
                    "unmuted" -> {
                        require(dto.until == null)
                        CollaborationNotificationMute.Unmuted
                    }
                    "forever" -> {
                        require(dto.until == null)
                        CollaborationNotificationMute.Forever
                    }
                    "until" ->
                        CollaborationNotificationMute.Until(
                            Instant.fromEpochMilliseconds(requireNotNull(dto.until))
                        )
                    else -> error("Invalid notification mute")
                }
            CollaborationNotificationSettingsState(
                    version,
                    dto.operationId,
                    CollaborationNotificationSettings(mute, enumValueOf(dto.preview)),
                )
                .also { require(validCollaborationNotificationState(it)) }
        } finally {
            plain.fill(0)
        }
    }

    suspend fun seal(state: CollaborationNotificationSettingsState): String {
        require(validCollaborationNotificationState(state))
        val mute = state.settings.mute
        val dto =
            NotificationSettingsDto(
                requireNotNull(state.operationId),
                when (mute) {
                    CollaborationNotificationMute.Unmuted -> "unmuted"
                    CollaborationNotificationMute.Forever -> "forever"
                    is CollaborationNotificationMute.Until -> "until"
                },
                (mute as? CollaborationNotificationMute.Until)?.at?.toEpochMilliseconds(),
                state.settings.preview.name,
            )
        val plain = Json.encodeToString(dto).encodeToByteArray(throwOnInvalidSequence = true)
        return try {
            require(plain.size <= 4096)
            Base64.encode(cipher.encrypt(key, aad(requireNotNull(state.version)), plain)).also {
                require(it.length <= 8192)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun aad(version: CollaborationNotificationSettingsRevision) =
        Json.encodeToString(
                listOf(
                    "hris-notification-settings-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    conversationId,
                    version.generation,
                    version.revision.toString(),
                )
            )
            .encodeToByteArray()
}
