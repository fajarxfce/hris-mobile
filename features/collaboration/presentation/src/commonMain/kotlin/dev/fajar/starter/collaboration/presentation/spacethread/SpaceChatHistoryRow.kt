package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.thread.*
import dev.fajar.starter.common.result.AppResult

/** A provider alias alone does not turn another namespace's UUID into this original. */
internal fun SpaceChatOutgoingMessage.matchesHistory(
    roomId: String,
    message: CollaborationHistoryMessage,
): Boolean {
    val sent = state as? CollaborationMessageState.Sent ?: return false
    val origin = sent.origin ?: return false
    val id =
        when (val source = identity.source) {
            is SpaceChatMessageSource.GroupCommand -> source.id
            is SpaceChatMessageSource.OriginalCommand -> source.id
            is SpaceChatMessageSource.History -> return false
        }
    return sent.device.roomId == roomId &&
        sent.eventId in message.eventIds &&
        message.logicalId == id &&
        message.sender == origin.sender &&
        message.contentHash == origin.contentHash &&
        (message.redacted || message.text == text)
}

/**
 * Applies already retained edits/redaction without changing original evidence or action identity.
 */
internal fun spaceChatHistoryRow(
    history: CollaborationSpaceThreadHistory,
    participants: CollaborationSpaceThreadParticipants,
    message: CollaborationHistoryMessage,
    matching: SpaceChatOutgoingMessage?,
): SpaceChatMessageRow {
    val selected = (participants.selectedIdentity as? AppResult.Success)?.value?.matrixUserId
    val original = (participants.originalIdentity as? AppResult.Success)?.value?.matrixUserId
    val identityComplete =
        selected != null &&
            (history.location !is CollaborationSpaceHistoryLocation.Original || original != null)
    val own = matching != null || message.sender == selected || message.sender == original
    val identity =
        matching?.identity
            ?: SpaceChatMessageIdentity(
                history.access.messageOwner(),
                history.conversationId,
                SpaceChatMessageSource.History(
                    history.location.roomId,
                    message.sender,
                    message.primaryEventId,
                ),
            )
    val kind =
        if (message.redacted) CollaborationMessageKind.REDACTED else CollaborationMessageKind.TEXT
    return SpaceChatMessageRow(
        identity,
        ChatMessageRow(
            key = identity.stableKey,
            eventId = message.primaryEventId,
            localId = null,
            timestamp = matching?.timestamp ?: message.timestamp,
            ownership =
                when {
                    own -> ChatMessageOwnership.SELF
                    identityComplete -> ChatMessageOwnership.COLLEAGUE
                    else -> ChatMessageOwnership.UNKNOWN
                },
            kind = kind,
            text = if (message.redacted) null else message.latestEdit?.edit?.text ?: message.text,
            delivery = CollaborationMessageDelivery.SENT,
            edited = !message.redacted && message.latestEdit != null,
            visibilityKnown = true,
            replyToEventId = message.replyToEventId.takeUnless { message.redacted },
            forwarded = !message.redacted && message.forwarded,
        ),
    )
}

internal fun SpaceChatOutgoingMessage.pendingRow(): SpaceChatMessageRow {
    val sent = state as? CollaborationMessageState.Sent
    return SpaceChatMessageRow(
        identity,
        ChatMessageRow(
            key = identity.stableKey,
            eventId = sent?.eventId,
            localId = null,
            timestamp = timestamp,
            ownership = ChatMessageOwnership.SELF,
            kind = CollaborationMessageKind.TEXT,
            text = text,
            delivery =
                when {
                    sent != null -> CollaborationMessageDelivery.SENT
                    failed -> CollaborationMessageDelivery.FAILED
                    else -> CollaborationMessageDelivery.PENDING
                },
            visibilityKnown = true,
            replyToEventId = replyToEventId,
            forwarded = forwarded,
        ),
    )
}
