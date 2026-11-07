package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*

internal fun CollaborationSdkTimelineItemDto.toDomain() =
    CollaborationTimelineItem(
        key = key,
        eventId = eventId,
        transactionId = transactionId,
        logicalId = logicalId,
        sender = sender,
        timestamp = timestamp,
        own = own,
        kind =
            when (kind) {
                SdkMessageKind.TEXT -> CollaborationMessageKind.TEXT
                SdkMessageKind.REDACTED -> CollaborationMessageKind.REDACTED
                SdkMessageKind.UNABLE_TO_DECRYPT -> CollaborationMessageKind.UNABLE_TO_DECRYPT
                SdkMessageKind.UNSUPPORTED -> CollaborationMessageKind.UNSUPPORTED
            },
        body = body,
        edited = edited,
        replyToEventId = replyToEventId,
        forwarded = forwarded,
        localEcho = localEcho,
        delivery =
            when (delivery) {
                SdkMessageDelivery.PENDING -> CollaborationMessageDelivery.PENDING
                SdkMessageDelivery.FAILED -> CollaborationMessageDelivery.FAILED
                SdkMessageDelivery.SENT -> CollaborationMessageDelivery.SENT
            },
    )
