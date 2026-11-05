package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

fun validCollaborationForwardSelection(selection: CollaborationForwardSelection): Boolean =
    validCollaborationVisibilitySelection(
        selection.conversationId,
        selection.roomId,
        selection.messages.map { it.reference },
        COLLABORATION_FORWARD_MESSAGE_LIMIT,
        false,
    ) &&
        selection.messages.all { validCollaborationText(it.text) } &&
        selection.messages
            .map { it.reference.sender to (it.reference.logicalId ?: it.reference.eventId) }
            .distinct()
            .size == selection.messages.size &&
        selection.destinationIds.size in 1..COLLABORATION_FORWARD_TARGET_LIMIT &&
        selection.destinationIds.all(::validCollaborationMessageId) &&
        selection.destinationIds.distinct().size == selection.destinationIds.size &&
        selection.messages.size * selection.destinationIds.size <= COLLABORATION_FORWARD_BATCH_LIMIT

fun validCollaborationForwardBatch(batch: CollaborationForwardBatch): Boolean {
    if (!validCollaborationForwardSelection(batch.selection)) return false
    val sources = batch.selection.messages
    val targets = batch.selection.destinationIds
    if (
        batch.intents.size != sources.size * targets.size ||
            batch.intents.map { it.id }.distinct().size != batch.intents.size
    )
        return false
    return batch.intents.withIndex().all { (index, intent) ->
        validCollaborationMessageId(intent.id) &&
            intent.owner == batch.access.messageOwner() &&
            intent.conversationId == targets[index / sources.size] &&
            intent.text == sources[index % sources.size].text &&
            intent.replyToEventId == null &&
            intent.forwarded &&
            intent.createdAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L
    }
}

internal fun CollaborationForwardSelection.detached() =
    copy(messages = messages.toList(), destinationIds = destinationIds.toList())
