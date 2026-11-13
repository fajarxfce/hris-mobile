package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Freeze and validate one coherent local target, independently of metadata acquisition. */
fun captureCollaborationSpaceInboxConversation(
    conversationId: String,
    current: CollaborationSpaceInboxConversation,
): AppResult<CollaborationSpaceInboxConversation> {
    val candidates = current.candidates.toList()
    val version = current.version
    val lastOrdinal = version?.lastOrdinal ?: 0L
    if (
        !validCollaborationMessageId(conversationId) ||
            (version != null &&
                (!validCollaborationMessageId(version.generation) ||
                    version.revision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
                    version.lastOrdinal !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)) ||
            candidates.size > 33 ||
            candidates.any {
                !validCollaborationSpaceInboxActivity(it) ||
                    it.preview == null ||
                    it.position.ordinal > lastOrdinal
            } ||
            candidates.map { it.source }.distinct().size != candidates.size ||
            candidates.zipWithNext().any { (a, b) ->
                compareCollaborationSpaceInboxPositions(a.position, b.position) <= 0
            } ||
            (current.draft != null &&
                (!validCollaborationSpaceInboxDraft(current.draft) ||
                    current.draft.value.conversationId != conversationId ||
                    current.draft.position.ordinal > lastOrdinal)) ||
            (version == null &&
                (current.entry != null || current.draft != null || candidates.isNotEmpty())) ||
            (current.entry == null && (current.draft != null || candidates.isNotEmpty())) ||
            (current.entry != null &&
                (current.entry.conversationId != conversationId ||
                    current.entry.createdAt !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER))
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    if (
        current.entry != null &&
            current.entry !=
                collaborationSpaceInboxEntry(
                    conversationId,
                    current.entry.createdAt,
                    current.draft,
                    candidates.firstOrNull(),
                )
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(current.copy(candidates = candidates))
}

/** Domain proposal only; the source repository must guard and publish both partitions. */
fun collaborationSpaceInboxDraftPublication(
    conversation: CollaborationSpaceConversationMetadata,
    current: CollaborationSpaceInboxConversation,
    replacement: CollaborationDraft,
): AppResult<CollaborationSpaceInboxPublication> {
    val captured = captureCollaborationSpaceInboxConversation(conversation.id, current)
    if (captured is AppResult.Failed) return captured
    val owned = (captured as AppResult.Success).value
    val createdAt = conversation.createdAt.toEpochMilliseconds()
    if (
        createdAt !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            replacement.conversationId != conversation.id ||
            !validCollaborationDraft(replacement) ||
            replacement.submissionId != null ||
            replacement.savedAt.toEpochMilliseconds() !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            (owned.entry != null && owned.entry.createdAt != createdAt)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val lastOrdinal = owned.version?.lastOrdinal ?: 0L
    val retained = owned.draft?.takeIf { it.value == replacement }
    if (retained == null && lastOrdinal == COLLABORATION_SPACE_INBOX_MAX_ORDER)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val draft =
        retained
            ?: CollaborationSpaceInboxDraft(
                replacement,
                CollaborationSpaceInboxPosition(
                    replacement.savedAt.toEpochMilliseconds(),
                    lastOrdinal + 1,
                ),
            )
    val publication =
        CollaborationSpaceInboxPublication(
            owned.version,
            if (retained == null) lastOrdinal + 1 else lastOrdinal,
            collaborationSpaceInboxEntry(
                conversation.id,
                createdAt,
                draft,
                owned.candidates.firstOrNull(),
            ),
            draft,
        )
    return if (validCollaborationSpaceInboxPublication(publication)) AppResult.Success(publication)
    else
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
