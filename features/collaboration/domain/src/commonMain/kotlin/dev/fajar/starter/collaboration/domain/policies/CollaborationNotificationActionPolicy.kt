package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationNotificationParent(parent: CollaborationNativeParent): Boolean =
    parent.sessionId.length in 1..128 &&
        parent.sessionId.all { it.code in 33..126 } &&
        parent.audience.length in 1..2048 &&
        validCollaborationMessageId(parent.accountId) &&
        validCollaborationMessageId(parent.nativeSessionId)

fun validCollaborationAuthenticatedNotificationAction(
    value: CollaborationAuthenticatedNotificationAction
): Boolean =
    validCollaborationNotificationParent(value.parent) &&
        validCollaborationNotificationAction(value.action)

/** Header and length only. Encoding and authentication remain the repository's responsibility. */
fun validCollaborationNotificationTokenShape(token: CollaborationNotificationActionToken): Boolean =
    token.encoded.length in 4..4096 &&
        (token.encoded.startsWith("v1.") || token.encoded.startsWith("v2."))

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
