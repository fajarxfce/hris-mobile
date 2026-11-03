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

@Serializable
private data class NotificationActionParentDto(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val nativeSessionId: String,
)

@Serializable
private data class NotificationActionEnvelopeDto(
    val parent: NotificationActionParentDto,
    val action: NotificationActionDto,
    val schema: Int = 2,
)

/** Legacy local envelopes have their parent only in AAD and remain strict-family reads. */
internal fun encodeNotificationAction(value: CollaborationNotificationAction): ByteArray =
    Json.encodeToString(actionDto(value)).encodeToByteArray(throwOnInvalidSequence = true)

internal fun decodeNotificationAction(plain: ByteArray): CollaborationNotificationAction =
    Json.decodeFromString<NotificationActionDto>(
            plain.decodeToString(throwOnInvalidSequence = true)
        )
        .action()

internal fun encodeNotificationActionEnvelope(
    parent: CollaborationNativeParent,
    action: CollaborationNotificationAction,
): ByteArray {
    require(validCollaborationNotificationParent(parent))
    return Json.encodeToString(
            NotificationActionEnvelopeDto(
                NotificationActionParentDto(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    parent.nativeSessionId,
                ),
                actionDto(action),
            )
        )
        .encodeToByteArray(throwOnInvalidSequence = true)
}

internal fun decodeNotificationActionEnvelope(
    plain: ByteArray
): CollaborationAuthenticatedNotificationAction {
    val dto =
        Json.decodeFromString<NotificationActionEnvelopeDto>(
            plain.decodeToString(throwOnInvalidSequence = true)
        )
    require(dto.schema == 2)
    return CollaborationAuthenticatedNotificationAction(
            CollaborationNativeParent(
                dto.parent.sessionId,
                dto.parent.audience,
                dto.parent.accountId,
                dto.parent.nativeSessionId,
            ),
            dto.action.action(),
        )
        .also { require(validCollaborationAuthenticatedNotificationAction(it)) }
}

private fun actionDto(value: CollaborationNotificationAction): NotificationActionDto {
    require(validCollaborationNotificationAction(value))
    val mute = value as? CollaborationNotificationAction.Mute
    return NotificationActionDto(
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
}

private fun NotificationActionDto.action(): CollaborationNotificationAction {
    require(schema == 1)
    val target =
        CollaborationNotificationActionTarget(
            companyId,
            conversationId,
            roomId,
            eventId,
            notificationId,
            renderId,
        )
    if (kind != "mute")
        require(expectedGeneration == null && expectedRevision == null && preview == null)
    val action =
        when (kind) {
            "reply" -> CollaborationNotificationAction.Reply(operationId, target)
            "read" -> CollaborationNotificationAction.MarkRead(operationId, target)
            "dismiss" -> CollaborationNotificationAction.Dismiss(operationId, target)
            "mute" -> {
                require((expectedGeneration == null) == (expectedRevision == null))
                val expected =
                    expectedGeneration?.let {
                        CollaborationNotificationSettingsRevision(
                            it,
                            requireNotNull(expectedRevision),
                        )
                    }
                CollaborationNotificationAction.Mute(
                    operationId,
                    target,
                    expected,
                    enumValueOf(requireNotNull(preview)),
                )
            }
            else -> error("Unsupported notification action")
        }
    require(validCollaborationNotificationAction(action))
    return action
}
