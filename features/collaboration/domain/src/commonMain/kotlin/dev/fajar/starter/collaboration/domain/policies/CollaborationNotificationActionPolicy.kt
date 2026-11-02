package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationNotificationAction(value: CollaborationNotificationAction): Boolean {
    val target = value.target
    return validCollaborationMessageId(value.operationId) &&
        validCollaborationMessageId(target.companyId) &&
        validCollaborationMessageId(target.notificationId) &&
        validCollaborationMessageId(target.renderId) &&
        validCollaborationReadSelection(
            target.conversationId,
            target.roomId,
            listOf(target.eventId),
            false,
        ) &&
        (value !is CollaborationNotificationAction.Mute ||
            validCollaborationNotificationRevision(value.expected))
}
