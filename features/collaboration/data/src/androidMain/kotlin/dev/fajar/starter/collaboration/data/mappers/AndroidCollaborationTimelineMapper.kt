package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationEventId
import org.matrix.rustcomponents.sdk.*

/** Copies primitive values only. The caller disposes the native timeline item/diff. */
internal fun TimelineItem.toCollaborationTimelineItem(): CollaborationSdkTimelineItemDto? {
    val event = asEvent() ?: return null
    try {
        val content = event.content as? TimelineItemContent.MsgLike ?: return null
        val message = (content.content.kind as? MsgLikeKind.Message)?.content
        val text = message?.takeIf { it.msgType is MessageType.Text && it.body.length <= 16_384 }
        val kind =
            when (content.content.kind) {
                is MsgLikeKind.Redacted -> SdkMessageKind.REDACTED
                is MsgLikeKind.UnableToDecrypt -> SdkMessageKind.UNABLE_TO_DECRYPT
                else -> if (text != null) SdkMessageKind.TEXT else SdkMessageKind.UNSUPPORTED
            }
        val id = event.eventOrTransactionId
        require(event.timestamp <= Long.MAX_VALUE.toULong())
        val key = uniqueId().id.also { require(it.length in 1..512) }
        val sender = event.sender.also { require(it.startsWith('@') && it.length in 2..255) }
        return CollaborationSdkTimelineItemDto(
            key = key,
            eventId = (id as? EventOrTransactionId.EventId)?.eventId,
            transactionId = (id as? EventOrTransactionId.TransactionId)?.transactionId,
            logicalId =
                if (kind == SdkMessageKind.TEXT)
                    collaborationSdkLogicalId(event.lazyProvider.latestJson())
                else null,
            sender = sender,
            timestamp = event.timestamp.toLong(),
            own = event.isOwn,
            kind = kind,
            body = text?.body,
            edited = message?.isEdited == true,
            replyToEventId =
                content.content.inReplyTo?.eventId()?.takeIf(::validCollaborationEventId),
            delivery =
                when (event.localSendState) {
                    is EventSendState.SendingFailed -> SdkMessageDelivery.FAILED
                    is EventSendState.NotSentYet -> SdkMessageDelivery.PENDING
                    else ->
                        if (event.isRemote || event.localSendState is EventSendState.Sent)
                            SdkMessageDelivery.SENT
                        else SdkMessageDelivery.PENDING
                },
        )
    } finally {
        event.destroy()
    }
}
