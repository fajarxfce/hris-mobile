package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationMessageEdit
import dev.fajar.starter.collaboration.domain.policies.*
import kotlinx.serialization.json.*

/** The original identity is supplied separately by the owned native timeline item. */
internal suspend fun CollaborationSdkReplacementDto.toMessageEdit(): CollaborationMessageEdit? {
    require(validCollaborationEventId(targetEventId) && validCollaborationHistorySender(sender))
    val value = parseCollaborationHistoryJson(json)
    require(value.historyText("sender") == sender)
    val id = requireNotNull(value.historyText("event_id"))
    val timestamp = value["origin_server_ts"] as? JsonPrimitive
    require(timestamp?.isString == false)
    val acceptedAt = requireNotNull(timestamp.longOrNull)
    val edit =
        CollaborationSdkHistoryEventDto(id, sender, acceptedAt, SdkMessageKind.TEXT, json)
            .toMessageEdit() ?: return null
    require(edit.targetEventId == targetEventId)
    return edit
}
