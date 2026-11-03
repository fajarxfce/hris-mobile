package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationNotificationReference(
    value: CollaborationNotificationPublicationReference
): Boolean {
    val parent = value.parent
    return validCollaborationNotificationPublicationId(value.id) &&
        validCollaborationMessageId(value.version.generation) &&
        value.version.revision in 0 until 9_007_199_254_740_991L &&
        parent.sessionId.length in 1..128 &&
        parent.sessionId.all { it.code in 33..126 } &&
        parent.audience.length in 1..2048 &&
        validCollaborationMessageId(parent.accountId) &&
        validCollaborationMessageId(parent.nativeSessionId) &&
        validCollaborationNotificationTarget(value.target)
}

internal fun CollaborationNotificationPublication.cleanupReference() =
    CollaborationNotificationPublicationReference(id, version, body.parent, body.target, body.state)

internal fun CollaborationNotificationPublication.cleanupReceipt() =
    CollaborationNotificationPublicationSettlement.Settled(
        id,
        version,
        body.target.notificationId,
        body.target.renderId,
        body.state,
    )
