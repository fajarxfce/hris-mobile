package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class NotificationActionDto(
    val operationId: String,
    val companyId: String,
    val conversationId: String,
    val roomId: String,
    val eventId: String,
    val notificationId: String,
    val renderId: String,
    val kind: String,
    val expectedGeneration: String? = null,
    val expectedRevision: Long? = null,
    val preview: String? = null,
    val schema: Int = 1,
)

internal fun encodeNotificationAction(value: CollaborationNotificationAction): ByteArray {
    require(validCollaborationNotificationAction(value))
    val mute = value as? CollaborationNotificationAction.Mute
    return Json.encodeToString(
            NotificationActionDto(
                value.operationId,
                value.target.companyId,
                value.target.conversationId,
                value.target.roomId,
                value.target.eventId,
                value.target.notificationId,
                value.target.renderId,
                when (value) {
                    is CollaborationNotificationAction.Reply -> "reply"
                    is CollaborationNotificationAction.MarkRead -> "read"
                    is CollaborationNotificationAction.Mute -> "mute"
                    is CollaborationNotificationAction.Dismiss -> "dismiss"
                },
                mute?.expected?.generation,
                mute?.expected?.revision,
                mute?.preview?.name,
            )
        )
        .encodeToByteArray(throwOnInvalidSequence = true)
}

internal fun decodeNotificationAction(plain: ByteArray): CollaborationNotificationAction {
    val dto =
        Json.decodeFromString<NotificationActionDto>(
            plain.decodeToString(throwOnInvalidSequence = true)
        )
    require(dto.schema == 1)
    val target =
        CollaborationNotificationActionTarget(
            dto.companyId,
            dto.conversationId,
            dto.roomId,
            dto.eventId,
            dto.notificationId,
            dto.renderId,
        )
    if (dto.kind != "mute")
        require(
            dto.expectedGeneration == null && dto.expectedRevision == null && dto.preview == null
        )
    val action =
        when (dto.kind) {
            "reply" -> CollaborationNotificationAction.Reply(dto.operationId, target)
            "read" -> CollaborationNotificationAction.MarkRead(dto.operationId, target)
            "dismiss" -> CollaborationNotificationAction.Dismiss(dto.operationId, target)
            "mute" -> {
                require((dto.expectedGeneration == null) == (dto.expectedRevision == null))
                val expected =
                    dto.expectedGeneration?.let {
                        CollaborationNotificationSettingsRevision(
                            it,
                            requireNotNull(dto.expectedRevision),
                        )
                    }
                CollaborationNotificationAction.Mute(
                    dto.operationId,
                    target,
                    expected,
                    enumValueOf(requireNotNull(dto.preview)),
                )
            }
            else -> error("Unsupported notification action")
        }
    require(validCollaborationNotificationAction(action))
    return action
}
