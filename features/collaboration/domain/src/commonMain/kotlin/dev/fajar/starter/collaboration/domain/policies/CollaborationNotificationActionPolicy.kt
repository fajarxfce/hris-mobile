package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationNotificationTarget(target: CollaborationNotificationActionTarget): Boolean =
    validCollaborationMessageId(target.companyId) &&
        validCollaborationMessageId(target.notificationId) &&
        validCollaborationMessageId(target.renderId) &&
        validCollaborationReadSelection(
            target.conversationId,
            target.roomId,
            listOf(target.eventId),
            false,
        )

fun validCollaborationNotificationAction(value: CollaborationNotificationAction): Boolean =
    validCollaborationMessageId(value.operationId) &&
        validCollaborationNotificationTarget(value.target) &&
        (value !is CollaborationNotificationAction.Mute ||
            validCollaborationNotificationRevision(value.expected))
