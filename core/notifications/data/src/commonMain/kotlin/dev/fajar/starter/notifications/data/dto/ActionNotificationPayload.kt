package dev.fajar.starter.notifications.data.dto

/** Native transport only. Opaque envelopes must be authenticated by the owning application. */
enum class ActionNotificationKind(val key: String) {
    Open("open"),
    Reply("reply"),
    MarkRead("read"),
    Mute("mute"),
    Dismiss("dismiss"),
}

data class ActionNotificationReference(val id: String, val renderId: String) {
    override fun toString() = "ActionNotificationReference([redacted])"
}

data class ActionNotificationPayload(
    val reference: ActionNotificationReference,
    val title: String,
    val body: String,
    val envelopes: Map<ActionNotificationKind, String>,
    val silent: Boolean,
) {
    override fun toString() = "ActionNotificationPayload([redacted])"
}

data class ActionNotificationInput(
    val reference: ActionNotificationReference,
    val kind: ActionNotificationKind,
    val envelope: String,
    val replyText: String? = null,
) {
    override fun toString() = "ActionNotificationInput([redacted])"
}

/**
 * Localized host copy, resolved when building each notification rather than retaining an Activity.
 */
data class ActionNotificationLabels(
    val channel: String,
    val privateTitle: String,
    val privateBody: String,
    val reply: String,
    val markRead: String,
    val mute: String,
)

enum class ActionNotificationSubmission {
    Submitted,
    PermissionRequired,
    ChannelDisabled,
    CapacityReached,
}

enum class ActionNotificationRemoval {
    Removed,
    Absent,
    Replaced,
}
