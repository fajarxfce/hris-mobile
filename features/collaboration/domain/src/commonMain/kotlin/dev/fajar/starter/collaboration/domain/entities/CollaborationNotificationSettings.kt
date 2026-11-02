package dev.fajar.starter.collaboration.domain.entities

import kotlin.time.Instant

enum class CollaborationNotificationPreview {
    Hidden,
    SenderOnly,
    Message,
}

sealed interface CollaborationNotificationMute {
    data object Unmuted : CollaborationNotificationMute

    data object Forever : CollaborationNotificationMute

    data class Until(val at: Instant) : CollaborationNotificationMute
}

/** Defaults conceal names and message content. Expired mutes remain stored without rewriting. */
data class CollaborationNotificationSettings(
    val mute: CollaborationNotificationMute = CollaborationNotificationMute.Unmuted,
    val preview: CollaborationNotificationPreview = CollaborationNotificationPreview.Hidden,
)

data class CollaborationNotificationSettingsRevision(val generation: String, val revision: Long)

data class CollaborationNotificationSettingsState(
    val version: CollaborationNotificationSettingsRevision? = null,
    val operationId: String? = null,
    val settings: CollaborationNotificationSettings = CollaborationNotificationSettings(),
) {
    override fun toString() = "CollaborationNotificationSettingsState([redacted])"
}

/** Keep the identical operation and observed version after an uncertain local response. */
data class CollaborationNotificationSettingsChange(
    val operationId: String,
    val expected: CollaborationNotificationSettingsRevision?,
    val settings: CollaborationNotificationSettings,
) {
    override fun toString() = "CollaborationNotificationSettingsChange([redacted])"
}
