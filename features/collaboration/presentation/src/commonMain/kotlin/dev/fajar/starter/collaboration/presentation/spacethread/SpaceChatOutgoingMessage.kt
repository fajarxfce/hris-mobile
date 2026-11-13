package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.presentation.thread.invalidThreadProjection
import dev.fajar.starter.common.result.*

/** Presentation of a real command or captured gesture, without converting its durable owner. */
internal data class SpaceChatOutgoingMessage(
    val identity: SpaceChatMessageIdentity,
    val timestamp: Long,
    val text: String,
    val replyToEventId: String?,
    val forwarded: Boolean,
    val state: CollaborationMessageState?,
    val failed: Boolean = false,
    val displayWhenUnmatched: Boolean = true,
) {
    override fun toString() = "SpaceChatOutgoingMessage([redacted])"
}

internal fun spaceChatOutgoingMessages(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    thread: CollaborationSpaceThreadSnapshot?,
    composer: SpaceChatComposerPresentation,
): AppResult<List<SpaceChatOutgoingMessage>> {
    val commands =
        when (thread) {
            is CollaborationSpaceThreadSnapshot.Bound -> thread.spaceCommands
            is CollaborationSpaceThreadSnapshot.Unresolved -> thread.spaceCommands
            null -> emptyList()
        }
    if (collaborationSpaceThreadCommands(access, conversationId, commands) is AppResult.Failed)
        return invalidThreadProjection()
    val originals = composer.pending + composer.represented
    if (
        composer.pending.size > 128 ||
            composer.represented.size > 128 ||
            originals.map { it.original.id }.distinct().size != originals.size
    )
        return invalidThreadProjection()
    val group = commands.associateBy { it.intent.id }.toMutableMap()
    for (item in originals) {
        if (
            !validCollaborationComposerSubmission(item.original) ||
                item.original.original.conversationId != conversationId
        )
            return invalidThreadProjection()
        val intent = collaborationSpaceComposerIntent(access, item.original)
        val selected =
            selectSpaceComposerDisplayRecord(
                access,
                intent,
                listOfNotNull(group[item.original.id], item.record),
            )
        if (selected is AppResult.Failed) return selected
        (selected as AppResult.Success).value?.let { group[item.original.id] = it }
    }
    val rows = mutableListOf<SpaceChatOutgoingMessage>()
    for (record in group.values) {
        val intent = record.intent
        if (intent.editTarget != null) continue
        val item = originals.firstOrNull { it.original.id == intent.id }
        rows +=
            SpaceChatOutgoingMessage(
                SpaceChatMessageIdentity(
                    access.messageOwner(),
                    conversationId,
                    SpaceChatMessageSource.GroupCommand(intent.id),
                ),
                intent.createdAt.toEpochMilliseconds(),
                intent.text,
                intent.replyToEventId,
                intent.forwarded,
                record.state,
                failed = item?.failure != null || item?.reviewRequired == true,
                displayWhenUnmatched = composer.represented.none { it.original.id == intent.id },
            )
    }
    for (item in composer.pending) {
        val original = item.original
        if (original.id in group) continue
        rows +=
            SpaceChatOutgoingMessage(
                SpaceChatMessageIdentity(
                    access.messageOwner(),
                    conversationId,
                    SpaceChatMessageSource.GroupCommand(original.id),
                ),
                original.submittedAt.toEpochMilliseconds(),
                original.original.text,
                original.original.replyToEventId,
                false,
                null,
                failed = item.failure != null || item.reviewRequired,
            )
    }
    val originalRows =
        spaceChatOriginalMessages(
            access,
            conversationId,
            thread as? CollaborationSpaceThreadSnapshot.Bound,
        )
    if (originalRows is AppResult.Failed) return originalRows
    rows += (originalRows as AppResult.Success).value

    return AppResult.Success(rows)
}

/** Adapts the original Company ledger without changing its physical owner or conversation. */
private fun spaceChatOriginalMessages(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    bound: CollaborationSpaceThreadSnapshot.Bound?,
): AppResult<List<SpaceChatOutgoingMessage>> {
    val rows = mutableListOf<SpaceChatOutgoingMessage>()
    val originalCommands = bound?.originalCommands.orEmpty()
    val location = bound?.history?.location as? CollaborationSpaceHistoryLocation.Original
    if (originalCommands.isNotEmpty() && location == null) return invalidThreadProjection()
    if (
        location != null &&
            collaborationSpaceOriginalThreadCommands(location, originalCommands) is AppResult.Failed
    )
        return invalidThreadProjection()
    for (record in originalCommands) {
        val intent = record.intent
        if (intent.editTarget != null) continue
        rows +=
            SpaceChatOutgoingMessage(
                SpaceChatMessageIdentity(
                    access.messageOwner(),
                    conversationId,
                    SpaceChatMessageSource.OriginalCommand(
                        intent.owner,
                        intent.conversationId,
                        intent.id,
                    ),
                ),
                intent.createdAt.toEpochMilliseconds(),
                intent.text,
                intent.replyToEventId,
                intent.forwarded,
                record.state,
            )
    }
    return AppResult.Success(rows)
}
