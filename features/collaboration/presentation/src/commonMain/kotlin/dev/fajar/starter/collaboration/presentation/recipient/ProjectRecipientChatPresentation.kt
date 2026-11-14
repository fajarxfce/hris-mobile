package dev.fajar.starter.collaboration.presentation.recipient

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.presentation.spacethread.isSpaceChatCommandRepresented
import dev.fajar.starter.collaboration.presentation.spacethread.selectSpaceComposerDisplayRecord
import dev.fajar.starter.collaboration.presentation.thread.invalidThreadProjection
import dev.fajar.starter.common.result.*

/**
 * Selects display facts only. Storage, delivery and admission stay outside this pure projection.
 */
internal fun projectRecipientChatPresentation(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    conversation: CollaborationSpaceConversationMetadata?,
    previous: RecipientChatPresentation,
    source: List<CollaborationSpaceRecipientSubmission>,
    provisional: List<RecipientComposerPendingSubmission>,
    progress: List<CollaborationSpaceRecipientMessageProgress>,
    thread: CollaborationSpaceThreadSnapshot?,
): AppResult<RecipientChatPresentation> {
    val captured =
        captureRecipientChatSources(
            access,
            peerAccountId,
            conversation,
            previous,
            source,
            provisional,
            progress,
            thread,
        )
    if (captured is AppResult.Failed) return captured
    val frame = (captured as AppResult.Success).value
    val receipts = progress.associateBy { it.original.id }
    val gestures = provisional.associateBy { it.original.id }
    val stored = source.map { it.id }.toSet()
    val retired = previous.represented.associateBy { it.original.id }.toMutableMap()
    val remaining = mutableListOf<RecipientChatPendingSubmission>()
    for (original in frame.originals) {
        val old = previous.pending.firstOrNull { it.original == original } ?: retired[original.id]
        val candidates =
            listOfNotNull(old?.record, receipts[original.id]?.record, frame.commands[original.id])
        val read =
            if (conversation == null) {
                if (candidates.isNotEmpty()) return invalidThreadProjection()
                AppResult.Success(null)
            } else
                selectSpaceComposerDisplayRecord(
                    access,
                    collaborationSpaceRecipientMessageIntent(access, original, conversation.id),
                    candidates,
                )
        if (read is AppResult.Failed) return read
        val record = (read as AppResult.Success).value
        val gesture = gestures[original.id]
        val item =
            RecipientChatPendingSubmission(
                original,
                durable =
                    old?.durable == true ||
                        gesture?.durable == true ||
                        original.id in stored ||
                        record != null,
                failure = if (gesture != null) gesture.failure else old?.failure,
                record = record,
            )
        if (retired[original.id] != null || isSpaceChatCommandRepresented(record, thread))
            retired[original.id] = item
        else remaining += item
    }
    if (remaining.size > 128) return invalidThreadProjection()
    return AppResult.Success(
        RecipientChatPresentation(
            pending =
                remaining.sortedWith(
                    compareBy<RecipientChatPendingSubmission> { it.original.submittedAt }
                        .thenBy { it.original.id }
                ),
            represented = retired.values.sortedByDescending { it.original.submittedAt }.take(128),
        )
    )
}

/** One bounded, validated source frame before selecting statuses or retaining display rows. */
internal data class CapturedRecipientChatSources(
    val commands: Map<String, CollaborationSpaceMessageRecord>,
    val originals: List<CollaborationSpaceRecipientSubmission>,
) {
    override fun toString() = "CapturedRecipientChatSources([redacted])"
}

internal fun captureRecipientChatSources(
    access: CollaborationSpaceLocalAccess,
    peerAccountId: String,
    conversation: CollaborationSpaceConversationMetadata?,
    previous: RecipientChatPresentation,
    source: List<CollaborationSpaceRecipientSubmission>,
    provisional: List<RecipientComposerPendingSubmission>,
    progress: List<CollaborationSpaceRecipientMessageProgress>,
    thread: CollaborationSpaceThreadSnapshot?,
): AppResult<CapturedRecipientChatSources> {
    if (
        !validCollaborationSpaceRecipientCoordinates(access, peerAccountId) ||
            previous.pending.size > 128 ||
            previous.represented.size > 128 ||
            source.size > 128 ||
            provisional.size > 128 ||
            progress.size > 128
    )
        return invalidThreadProjection()
    if (
        conversation != null &&
            (!validCollaborationSpaceConversationMetadata(access, conversation) ||
                peerAccountId !in listOf(conversation.firstAccountId, conversation.secondAccountId))
    )
        return invalidThreadProjection()
    if (thread != null && thread.conversation != conversation) return invalidThreadProjection()
    val commands =
        when (thread) {
            is CollaborationSpaceThreadSnapshot.Bound -> thread.spaceCommands
            is CollaborationSpaceThreadSnapshot.Unresolved -> thread.spaceCommands
            null -> emptyList()
        }
    if (
        commands.size > 128 ||
            commands.map { it.intent.id }.distinct().size != commands.size ||
            commands.any {
                !validCollaborationSpaceMessageRecord(access, it) ||
                    it.intent.conversationId != conversation?.id
            }
    )
        return invalidThreadProjection()
    val inputs =
        listOf(
            previous.pending.map { it.original },
            previous.represented.map { it.original },
            source.toList(),
            provisional.map { it.original },
            progress.map { it.original },
        )
    if (
        inputs.any { list ->
            list.map { it.id }.distinct().size != list.size ||
                list.any {
                    !validCollaborationSpaceRecipientSubmission(it) ||
                        it.original.peerAccountId != peerAccountId
                }
        }
    )
        return invalidThreadProjection()
    val exact = mutableMapOf<String, CollaborationSpaceRecipientSubmission>()
    for (original in inputs.flatten()) {
        if (exact[original.id]?.let { it != original } == true) return invalidThreadProjection()
        exact[original.id] = original
    }
    for (item in previous.pending + previous.represented) {
        val record = item.record ?: continue
        if (
            conversation == null ||
                record.intent.conversationId != conversation.id ||
                !matchesCollaborationSpaceRecipientSubmission(access, record, item.original)
        )
            return invalidThreadProjection()
    }
    if (!validRecipientChatHistory(access, conversation, thread)) return invalidThreadProjection()
    val captured =
        (previous.pending.map { it.original } +
                source +
                provisional.map { it.original } +
                progress.map { it.original })
            .distinctBy { it.id }
    return AppResult.Success(
        CapturedRecipientChatSources(commands.associateBy { it.intent.id }, captured)
    )
}

/** Defensive display-snapshot validation, not a substitute for the admitted domain observer. */
private fun validRecipientChatHistory(
    access: CollaborationSpaceLocalAccess,
    conversation: CollaborationSpaceConversationMetadata?,
    thread: CollaborationSpaceThreadSnapshot?,
): Boolean {
    if (thread == null) return true
    val conversationId = conversation?.id ?: return false
    if (thread is CollaborationSpaceThreadSnapshot.Unresolved)
        return thread.access.messageOwner() == access.messageOwner()
    val history = (thread as CollaborationSpaceThreadSnapshot.Bound).history
    if (
        history.access.messageOwner() != access.messageOwner() ||
            history.conversationId != conversationId ||
            history.page.messages.size > 500 ||
            history.page.messages.any { !validCollaborationHistoryMessage(it) } ||
            (history.page.roomId != history.location.roomId && history.page.messages.isNotEmpty())
    )
        return false
    return when (val location = history.location) {
        is CollaborationSpaceHistoryLocation.Space ->
            location.access.messageOwner() == access.messageOwner() &&
                location.conversationId == conversationId
        is CollaborationSpaceHistoryLocation.Original ->
            location.access.audience == access.audience &&
                location.access.accountId == access.accountId &&
                validCollaborationSpaceConversationId(location.access.companyId) &&
                validCollaborationSpaceConversationId(location.conversationId)
    }
}
