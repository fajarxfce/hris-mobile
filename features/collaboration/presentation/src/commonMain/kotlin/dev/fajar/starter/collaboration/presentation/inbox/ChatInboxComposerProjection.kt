package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.*

/** Bounded preview presentation; retained watches bridge independent local stream publications. */
internal data class ChatInboxComposerProjection(
    val previews: Map<String, CollaborationComposerMessageProgress>,
    val originals: List<CollaborationComposerSubmission>,
)

internal fun chatInboxComposerProjection(
    ids: Set<String>,
    previews: Map<String, CollaborationInboxPreview>,
    progress: List<CollaborationComposerMessageProgress>,
): ChatInboxComposerProjection {
    val retained =
        progress.filter { item ->
            val id = item.original.original.conversationId
            id in ids &&
                item.record?.state != CollaborationMessageState.Cancelled &&
                !representedComposerPreview(previews[id], item)
        }
    val latest =
        retained
            .groupBy { it.original.original.conversationId }
            .mapValues { (_, rows) ->
                rows.maxWith(
                    compareBy<CollaborationComposerMessageProgress> { it.original.submittedAt }
                        .thenBy { it.original.id }
                )
            }
    return ChatInboxComposerProjection(latest, latest.values.map { it.original }.sortedBy { it.id })
}

private fun representedComposerPreview(
    preview: CollaborationInboxPreview?,
    item: CollaborationComposerMessageProgress,
): Boolean {
    val outgoing = preview?.outgoing
    if (outgoing != null && outgoing.intent.createdAt >= item.original.submittedAt) return true
    val message = preview?.message ?: return false
    val sent = item.record?.state as? CollaborationMessageState.Sent ?: return false
    return sent.eventId in message.eventIds ||
        item.original.submittedAt.toEpochMilliseconds() <= message.timestamp
}
