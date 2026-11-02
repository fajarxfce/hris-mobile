package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

internal enum class CollaborationNotificationReplacement {
    Replace,
    Reuse,
    Ignore,
    Conflict,
}

/** Deterministic acquired-original presentation watermark, never a causal provider stream order. */
internal fun collaborationNotificationReplacement(
    parent: CollaborationNativeParent,
    message: CollaborationMessageNotification,
    original: CollaborationHistoryMessage,
    previous: CollaborationNotificationPublication?,
): CollaborationNotificationReplacement {
    if (
        !validCollaborationHistoryMessage(original) ||
            original.redacted ||
            original.primaryEventId != message.eventId ||
            original.contentHash != message.contentHash
    )
        return CollaborationNotificationReplacement.Conflict
    if (previous == null) return CollaborationNotificationReplacement.Replace
    if (!validCollaborationNotificationPublication(previous))
        return CollaborationNotificationReplacement.Conflict
    val body = previous.body
    if (
        body.parent.accountId != parent.accountId ||
            body.parent.audience != parent.audience ||
            body.target.companyId != message.access.companyId ||
            body.target.conversationId != message.conversationId ||
            body.target.roomId != message.roomId
    )
        return CollaborationNotificationReplacement.Conflict
    if (body.target.eventId == message.eventId) {
        if (body.timestamp != original.timestamp || body.contentHash != original.contentHash)
            return CollaborationNotificationReplacement.Conflict
        if (!body.state.isActivePublication()) return CollaborationNotificationReplacement.Ignore
        return if (body.parent == parent && body.message == message)
            CollaborationNotificationReplacement.Reuse
        else CollaborationNotificationReplacement.Replace
    }
    val order =
        compareValues(original.timestamp, body.timestamp).takeIf { it != 0 }
            ?: compareValues(original.primaryEventId, body.target.eventId)
    return if (order <= 0) CollaborationNotificationReplacement.Ignore
    else CollaborationNotificationReplacement.Replace
}

internal fun validCollaborationPublicationActions(
    body: CollaborationNotificationPublicationBody,
    actions: List<CollaborationNotificationAction>,
): Boolean {
    if (
        actions.size != 4 ||
            !actions.all(::validCollaborationNotificationAction) ||
            actions.map { it.operationId }.distinct().size != 4 ||
            actions.any { it.target != body.target }
    )
        return false
    if (
        actions[0] !is CollaborationNotificationAction.Reply ||
            actions[1] !is CollaborationNotificationAction.MarkRead ||
            actions[3] !is CollaborationNotificationAction.Dismiss
    )
        return false
    val mute = actions[2] as? CollaborationNotificationAction.Mute ?: return false
    val message = body.message ?: return false
    return mute.expected == message.settingsVersion && mute.preview == message.preview
}
