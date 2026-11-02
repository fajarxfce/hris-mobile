package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Plain text for native rendering; bound retained previews without splitting UTF-16 pairs. */
fun collaborationNotificationPreviewText(value: String, limit: Int): String {
    require(limit in 1..400)
    val selected = buildString {
        for (index in 0 until minOf(value.length, 4096)) {
            val char = value[index]
            if (length >= limit) break
            if (!char.isISOControl() || char == '\n' || char == '\t') append(char)
        }
    }
    return (if (selected.lastOrNull()?.isHighSurrogate() == true) selected.dropLast(1)
        else selected)
        .trim()
}

/** Projects already validated immutable source snapshots without I/O or publication authority. */
fun collaborationNotificationMessage(
    access: CollaborationLocalAccess,
    summary: CollaborationDirectSummary,
    roomId: String,
    original: CollaborationHistoryMessage,
    preference: CollaborationNotificationSettingsState,
    read: CollaborationReadState,
): CollaborationMessageNotification {
    val preview = preference.settings.preview
    return CollaborationMessageNotification(
        access,
        summary.conversation.id,
        roomId,
        original.primaryEventId,
        original.contentHash,
        preference.version,
        read.version,
        preview,
        if (preview == CollaborationNotificationPreview.Hidden) null
        else collaborationNotificationPreviewText(summary.peer.displayName, 160),
        if (preview == CollaborationNotificationPreview.Message)
            collaborationNotificationPreviewText(requireNotNull(original.text), 400)
        else null,
    )
}
