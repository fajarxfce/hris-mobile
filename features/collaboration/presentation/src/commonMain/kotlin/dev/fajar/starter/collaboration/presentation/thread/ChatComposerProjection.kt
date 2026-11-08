package dev.fajar.starter.collaboration.presentation.thread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationCancellation
import dev.fajar.starter.common.result.*

/** Actual command records and provisional presentation stay distinct through local handoff. */
internal data class ChatComposerProjection(
    val records: List<CollaborationMessageRecord>,
    val pendingRows: List<ChatMessageRow>,
)

internal fun projectChatComposer(
    owner: CollaborationMessageOwner,
    conversationId: String,
    pending: List<CollaborationMessageRecord>,
    composer: List<CollaborationComposerMessageProgress>,
    provisional: List<ChatComposerPendingSubmission>,
): AppResult<ChatComposerProjection> {
    if (pending.size > 128) return invalidThreadProjection()
    if (pending.map { it.intent.id }.distinct().size != pending.size)
        return invalidThreadProjection()
    if (pending.any { it.intent.owner != owner || it.intent.conversationId != conversationId })
        return invalidThreadProjection()
    if (pending.any { it.state == CollaborationMessageState.Cancelled })
        return invalidThreadProjection()

    if (
        composer.size > 256 ||
            provisional.size > 128 ||
            composer.map { it.original.id }.distinct().size != composer.size ||
            provisional.map { it.original.id }.distinct().size != provisional.size
    )
        return invalidThreadProjection()
    val originals = composer.associateBy { it.original.id }
    if (
        provisional.any { item ->
            originals[item.original.id]?.original?.let { it != item.original } == true
        }
    )
        return invalidThreadProjection()
    val retained = pending.associateBy { it.intent.id }.toMutableMap()
    for (item in composer) {
        val original = item.original
        if (original.original.conversationId != conversationId) return invalidThreadProjection()
        val expected =
            CollaborationMessageIntent(
                original.id,
                owner,
                conversationId,
                original.submittedAt,
                original.original.text,
                replyToEventId = original.original.replyToEventId,
            )
        if (retained[original.id]?.intent?.let { it != expected } == true)
            return invalidThreadProjection()
        val record = item.record ?: continue
        if (
            record.intent.owner != owner ||
                record.intent.conversationId != conversationId ||
                record.intent.id != original.id ||
                record.intent.text != original.original.text ||
                record.intent.createdAt != original.submittedAt ||
                record.intent.replyToEventId != original.original.replyToEventId ||
                record.intent.forwarded ||
                record.intent.editTarget != null
        )
            return invalidThreadProjection()
        val previous = retained[original.id]
        if (previous != null && previous.intent != record.intent) return invalidThreadProjection()
        if (record.state == CollaborationMessageState.Cancelled) {
            if (previous != null && !validCollaborationCancellation(previous))
                return invalidThreadProjection()
            retained.remove(original.id)
        } else if (previous == null || record.revision >= previous.revision)
            retained[original.id] = record
    }
    val rows = mutableListOf<ChatMessageRow>()

    val captured = provisional.associateBy { it.original.id }
    val candidates =
        (composer.map { it.original } + provisional.map { it.original }).distinctBy { it.id }
    for (original in candidates) {
        if (original.original.conversationId != conversationId) return invalidThreadProjection()
        val progress = originals[original.id]
        if (progress?.record?.state == CollaborationMessageState.Cancelled) continue
        if (original.id in retained) continue
        val failed = progress?.reviewRequired == true || captured[original.id]?.failure != null
        rows +=
            ChatMessageRow(
                key = "outgoing:${original.id}",
                eventId = null,
                localId = original.id,
                timestamp = original.submittedAt.toEpochMilliseconds(),
                ownership = ChatMessageOwnership.SELF,
                kind = CollaborationMessageKind.TEXT,
                text = original.original.text,
                delivery =
                    if (failed) CollaborationMessageDelivery.FAILED
                    else CollaborationMessageDelivery.PENDING,
                canCancel = true,
                visibilityKnown = true,
                replyToEventId = original.original.replyToEventId,
                composerSubmission = original,
            )
    }
    return AppResult.Success(ChatComposerProjection(retained.values.toList(), rows))
}
