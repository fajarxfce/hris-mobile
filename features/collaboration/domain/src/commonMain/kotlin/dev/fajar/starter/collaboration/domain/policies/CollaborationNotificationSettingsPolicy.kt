package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Instant

fun validCollaborationNotificationRevision(value: CollaborationNotificationSettingsRevision?) =
    value == null ||
        (validCollaborationMessageId(value.generation) &&
            value.revision in 0 until 9_007_199_254_740_991L)

fun validCollaborationNotificationSettings(value: CollaborationNotificationSettings): Boolean =
    when (val mute = value.mute) {
        CollaborationNotificationMute.Unmuted,
        CollaborationNotificationMute.Forever -> true
        is CollaborationNotificationMute.Until ->
            mute.at.toEpochMilliseconds() in 0..253_402_300_799_999L &&
                mute.at == Instant.fromEpochMilliseconds(mute.at.toEpochMilliseconds())
    }

fun validCollaborationNotificationState(value: CollaborationNotificationSettingsState) =
    validCollaborationNotificationRevision(value.version) &&
        validCollaborationNotificationSettings(value.settings) &&
        if (value.version == null) value == CollaborationNotificationSettingsState()
        else value.operationId?.let(::validCollaborationMessageId) == true

/** This decision never modifies settings, local read coverage or receipt-sharing preferences. */
fun CollaborationNotificationSettings.isMutedAt(now: Instant): Boolean =
    when (val current = mute) {
        CollaborationNotificationMute.Unmuted -> false
        CollaborationNotificationMute.Forever -> true
        is CollaborationNotificationMute.Until -> now < current.at
    }
