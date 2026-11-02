package dev.fajar.starter.collaboration.data.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import kotlinx.serialization.Serializable

@Serializable
internal data class PublicationReference(val id: String, val revision: Long, val state: String)

@Serializable
internal data class PublicationCheckpointDto(
    val active: List<PublicationReference>,
    val schema: Int = 1,
)

@Serializable
internal data class PublicationParentDto(
    val sessionId: String,
    val audience: String,
    val accountId: String,
    val nativeSessionId: String,
)

@Serializable
internal data class PublicationTargetDto(
    val companyId: String,
    val conversationId: String,
    val roomId: String,
    val eventId: String,
    val notificationId: String,
    val renderId: String,
)

@Serializable
internal data class PublicationMessageDto(
    val preview: String,
    val sender: String?,
    val body: String?,
    val settingsGeneration: String?,
    val settingsRevision: Long?,
    val readGeneration: String?,
    val readRevision: Long?,
)

@Serializable
internal data class PublicationActionsDto(
    val reply: String,
    val markRead: String,
    val mute: String,
    val dismiss: String,
)

@Serializable
internal data class PublicationBodyDto(
    val parent: PublicationParentDto,
    val target: PublicationTargetDto,
    val contentHash: String,
    val timestamp: Long,
    val state: String,
    val message: PublicationMessageDto?,
    val actions: PublicationActionsDto?,
    val schema: Int = 1,
)

internal fun publicationDto(value: CollaborationNotificationPublicationBody) =
    PublicationBodyDto(
        value.parent.let {
            PublicationParentDto(it.sessionId, it.audience, it.accountId, it.nativeSessionId)
        },
        value.target.let {
            PublicationTargetDto(
                it.companyId,
                it.conversationId,
                it.roomId,
                it.eventId,
                it.notificationId,
                it.renderId,
            )
        },
        value.contentHash,
        value.timestamp,
        value.state.name,
        value.message?.let {
            PublicationMessageDto(
                it.preview.name,
                it.senderName,
                it.body,
                it.settingsVersion?.generation,
                it.settingsVersion?.revision,
                it.readVersion?.generation,
                it.readVersion?.revision,
            )
        },
        value.actions?.let {
            PublicationActionsDto(
                it.reply.encoded,
                it.markRead.encoded,
                it.mute.encoded,
                it.dismiss.encoded,
            )
        },
    )

internal fun PublicationBodyDto.body(): CollaborationNotificationPublicationBody {
    require(schema == 1)
    val parent =
        parent.let {
            CollaborationNativeParent(it.sessionId, it.audience, it.accountId, it.nativeSessionId)
        }
    val target =
        target.let {
            CollaborationNotificationActionTarget(
                it.companyId,
                it.conversationId,
                it.roomId,
                it.eventId,
                it.notificationId,
                it.renderId,
            )
        }
    return CollaborationNotificationPublicationBody(
        parent,
        target,
        contentHash,
        timestamp,
        enumValueOf<CollaborationNotificationPublicationState>(state),
        message?.let {
            require((it.settingsGeneration == null) == (it.settingsRevision == null))
            require((it.readGeneration == null) == (it.readRevision == null))
            CollaborationMessageNotification(
                CollaborationLocalAccess(
                    parent.sessionId,
                    parent.audience,
                    parent.accountId,
                    target.companyId,
                ),
                target.conversationId,
                target.roomId,
                target.eventId,
                contentHash,
                it.settingsGeneration?.let { generation ->
                    CollaborationNotificationSettingsRevision(
                        generation,
                        requireNotNull(it.settingsRevision),
                    )
                },
                it.readGeneration?.let { generation ->
                    CollaborationReadStateRevision(generation, requireNotNull(it.readRevision))
                },
                enumValueOf<CollaborationNotificationPreview>(it.preview),
                it.sender,
                it.body,
            )
        },
        actions?.let {
            CollaborationNotificationPublicationActions(
                CollaborationNotificationActionToken(it.reply),
                CollaborationNotificationActionToken(it.markRead),
                CollaborationNotificationActionToken(it.mute),
                CollaborationNotificationActionToken(it.dismiss),
            )
        },
    )
}
