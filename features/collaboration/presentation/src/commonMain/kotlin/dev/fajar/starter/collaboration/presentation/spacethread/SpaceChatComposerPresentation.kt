package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.presentation.thread.invalidThreadProjection
import dev.fajar.starter.common.result.*

/**
 * Route-owned continuity between independent local publications, never a durable command journal.
 */
data class SpaceChatComposerPresentation(
    val pending: List<SpaceChatPendingSubmission> = emptyList(),
    /** Recent exact terminal rows keep visual identity and suppress stale outbox publications. */
    val represented: List<SpaceChatPendingSubmission> = emptyList(),
) {
    override fun toString() = "SpaceChatComposerPresentation([redacted])"
}

/** Presentation selection only; this never acknowledges, retries, cancels or retires storage. */
internal fun projectSpaceChatComposer(
    access: CollaborationSpaceLocalAccess,
    conversationId: String,
    previous: SpaceChatComposerPresentation,
    outbox: List<CollaborationComposerOutboxEntry>,
    provisional: List<SpaceChatPendingSubmission>,
    progress: List<CollaborationSpaceComposerMessageProgress>,
    thread: CollaborationSpaceThreadSnapshot?,
): AppResult<SpaceChatComposerPresentation> {
    if (
        previous.pending.size > 128 ||
            previous.represented.size > 128 ||
            outbox.size > 128 ||
            provisional.size > 128 ||
            progress.size > 128
    )
        return invalidThreadProjection()
    val commands =
        when (thread) {
            is CollaborationSpaceThreadSnapshot.Bound -> thread.spaceCommands
            is CollaborationSpaceThreadSnapshot.Unresolved -> thread.spaceCommands
            null -> emptyList()
        }
    val inputs =
        listOf(
            previous.pending.map { it.original },
            previous.represented.map { it.original },
            outbox.map { it.submission },
            provisional.map { it.original },
            progress.map { it.original },
        )
    if (
        inputs.any { list ->
            list.map { it.id }.distinct().size != list.size ||
                list.any {
                    !validCollaborationComposerSubmission(it) ||
                        it.original.conversationId != conversationId
                }
        }
    )
        return invalidThreadProjection()
    val exact = mutableMapOf<String, CollaborationComposerSubmission>()
    for (original in inputs.flatten()) {
        if (exact[original.id]?.let { it != original } == true) return invalidThreadProjection()
        exact[original.id] = original
    }
    if (
        commands.size > 128 ||
            commands.map { it.intent.id }.distinct().size != commands.size ||
            commands.any {
                !validCollaborationSpaceMessageRecord(access, it) ||
                    it.intent.conversationId != conversationId
            }
    )
        return invalidThreadProjection()
    val records = progress.associateBy { it.original.id }
    val gestures = provisional.associateBy { it.original.id }
    val stored = outbox.associateBy { it.submission.id }
    val retired = previous.represented.associateBy { it.original.id }.toMutableMap()
    val captured =
        (previous.pending.map { it.original } +
                outbox.map { it.submission } +
                provisional.map { it.original })
            .distinctBy { it.id }
    val remaining = mutableListOf<SpaceChatPendingSubmission>()
    for (original in captured) {
        val intent = collaborationSpaceComposerIntent(access, original)
        val old = previous.pending.firstOrNull { it.original == original } ?: retired[original.id]
        val candidates =
            listOfNotNull(
                old?.record,
                records[original.id]?.record,
                commands.firstOrNull { it.intent.id == original.id },
            )
        val selected = selectSpaceComposerDisplayRecord(access, intent, candidates)
        if (selected is AppResult.Failed) return selected
        val record = (selected as AppResult.Success).value
        val gesture = gestures[original.id]
        val item =
            SpaceChatPendingSubmission(
                original,
                durable =
                    old?.durable == true ||
                        gesture?.durable == true ||
                        original.id in stored ||
                        record != null,
                failure = if (gesture != null) gesture.failure else old?.failure,
                record = record,
                reviewRequired = stored[original.id]?.reviewRequired ?: old?.reviewRequired ?: false,
            )
        if (retired[original.id] != null || spaceComposerRepresented(item, thread))
            retired[original.id] = item
        else remaining += item
    }
    if (remaining.size > 128) return invalidThreadProjection()
    return AppResult.Success(
        SpaceChatComposerPresentation(
            pending =
                remaining.sortedWith(
                    compareBy<SpaceChatPendingSubmission> { it.original.submittedAt }
                        .thenBy { it.original.id }
                ),
            represented = retired.values.sortedByDescending { it.original.submittedAt }.take(128),
        )
    )
}

/** Selects display status from exact local revisions; it never advances a durable command. */
internal fun selectSpaceComposerDisplayRecord(
    access: CollaborationSpaceLocalAccess,
    intent: CollaborationSpaceMessageIntent,
    candidates: List<CollaborationSpaceMessageRecord>,
): AppResult<CollaborationSpaceMessageRecord?> {
    if (!validCollaborationSpaceMessageIntent(access, intent)) return invalidThreadProjection()
    if (candidates.any { it.intent != intent || !validCollaborationSpaceMessageRecord(access, it) })
        return invalidThreadProjection()
    // A stale active inventory cannot replace a later exact record or a confirmed cancellation.
    val revision = candidates.maxOfOrNull { it.revision }
    val newest = candidates.filter { it.revision == revision }.distinct()
    val record =
        newest.firstOrNull { it.state == CollaborationMessageState.Cancelled }
            ?: newest.firstOrNull()
    if (
        newest.size > 1 &&
            !(newest.size == 2 &&
                newest.all {
                    it.state == CollaborationMessageState.Cancelled ||
                        it.state == CollaborationMessageState.Queued
                })
    )
        return invalidThreadProjection()
    return AppResult.Success(record)
}

/**
 * Provider aliases and immutable origin prove this Group original; another namespace's UUID cannot.
 */
private fun spaceComposerRepresented(
    item: SpaceChatPendingSubmission,
    thread: CollaborationSpaceThreadSnapshot?,
): Boolean {
    val record = item.record ?: return false
    if (record.state == CollaborationMessageState.Cancelled) return true
    val sent = record.state as? CollaborationMessageState.Sent ?: return false
    val origin = sent.origin ?: return false
    val bound = thread as? CollaborationSpaceThreadSnapshot.Bound ?: return false
    val history = bound.history
    if (
        history.conversationId != record.intent.conversationId ||
            history.location.roomId != sent.device.roomId ||
            history.access.messageOwner() != record.intent.owner ||
            history.page.roomId != sent.device.roomId
    )
        return false
    return history.page.messages.any {
        sent.eventId in it.eventIds &&
            it.sender == origin.sender &&
            it.logicalId == record.intent.id &&
            it.contentHash == origin.contentHash &&
            (it.redacted || it.text == record.intent.text)
    }
}
