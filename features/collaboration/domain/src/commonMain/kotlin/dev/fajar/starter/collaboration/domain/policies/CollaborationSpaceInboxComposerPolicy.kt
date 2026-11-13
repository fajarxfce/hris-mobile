package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*

/** Exact retained original lookup, independent of a mutable inbox page or metadata. */
fun captureCollaborationSpaceInboxComposerAnchor(
    submissionId: String,
    current: CollaborationSpaceInboxActivities,
): AppResult<CollaborationSpaceInboxActivity?> {
    val values = current.values.toList()
    val version = current.version
    if (
        !validCollaborationMessageId(submissionId) ||
            (version != null &&
                (!validCollaborationMessageId(version.generation) ||
                    version.revision !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
                    version.lastOrdinal !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER)) ||
            values.size > 1 ||
            (version == null && values.isNotEmpty()) ||
            values.any {
                it.source != CollaborationSpaceInboxSource.GroupOriginal(submissionId) ||
                    !validCollaborationSpaceInboxActivity(it) ||
                    it.position.ordinal > (version?.lastOrdinal ?: 0)
            }
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    return AppResult.Success(values.singleOrNull())
}

/** A new original and its editor successor share one index version and insertion sequence. */
fun collaborationSpaceInboxComposerPublication(
    access: CollaborationSpaceLocalAccess,
    conversation: CollaborationSpaceConversationMetadata,
    current: CollaborationSpaceInboxConversation,
    anchors: CollaborationSpaceInboxActivities,
    submission: CollaborationComposerSubmission,
    editor: CollaborationDraft,
    retained: Boolean,
): AppResult<CollaborationSpaceInboxPublication> {
    val captured = captureCollaborationSpaceInboxConversation(conversation.id, current)
    if (captured is AppResult.Failed) return captured
    val owned = (captured as AppResult.Success).value
    val actualAnchors = anchors.values.toList()
    if (anchors.version != owned.version)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    val source = CollaborationSpaceInboxSource.GroupOriginal(submission.id)
    val intent = collaborationSpaceComposerIntent(access, submission)
    if (
        actualAnchors.size > 1 ||
            actualAnchors.any {
                it.source != source ||
                    !validCollaborationSpaceInboxActivity(it) ||
                    it.position.ordinal > (owned.version?.lastOrdinal ?: 0)
            } ||
            (actualAnchors.isNotEmpty() && owned.entry == null) ||
            !validCollaborationComposerHandoff(submission, null) ||
            !validCollaborationSpaceMessageIntent(access, intent) ||
            submission.original.conversationId != conversation.id ||
            !validCollaborationDraft(editor) ||
            editor.conversationId != conversation.id ||
            (!retained && (editor != submission.nextDraft || actualAnchors.isNotEmpty()))
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val oldAnchor = actualAnchors.singleOrNull()
    val visibleAnchor = owned.candidates.singleOrNull { it.source == source }
    if (visibleAnchor != null && visibleAnchor != oldAnchor)
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val createdAt = conversation.createdAt.toEpochMilliseconds()
    if (
        createdAt !in 0..COLLABORATION_SPACE_INBOX_MAX_ORDER ||
            (owned.entry != null && owned.entry.createdAt != createdAt)
    )
        return collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
    val oldDraft = owned.draft?.takeIf { it.value == editor }
    val allocated = (if (oldDraft == null) 1 else 0) + (if (oldAnchor == null) 1 else 0)
    val lastOrdinal = owned.version?.lastOrdinal ?: 0L
    if (lastOrdinal > COLLABORATION_SPACE_INBOX_MAX_ORDER - allocated)
        return collaborationRecoveryFailure("client.collaboration_inbox_busy", FailureKind.Conflict)
    var ordinal = lastOrdinal
    val draft =
        oldDraft
            ?: CollaborationSpaceInboxDraft(
                editor,
                CollaborationSpaceInboxPosition(editor.savedAt.toEpochMilliseconds(), ++ordinal),
            )
    val activity =
        oldAnchor
            ?: CollaborationSpaceInboxActivity(
                source,
                CollaborationSpaceInboxPosition(
                    maxOf(
                        createdAt,
                        submission.submittedAt.toEpochMilliseconds(),
                        owned.entry?.position?.timestamp ?: 0,
                    ),
                    ++ordinal,
                ),
                CollaborationSpaceInboxPreview(
                    CollaborationSpaceInboxContent.TEXT,
                    submission.original.text,
                    submission.submittedAt.toEpochMilliseconds(),
                    CollaborationMessageDelivery.PENDING,
                ),
            )
    val candidates =
        owned.candidates.filter { it.source != source } +
            listOfNotNull(activity.takeIf { it.preview != null })
    val newest =
        candidates.maxWithOrNull { a, b ->
            compareCollaborationSpaceInboxPositions(a.position, b.position)
        }
    val proposal =
        CollaborationSpaceInboxPublication(
            owned.version,
            ordinal,
            collaborationSpaceInboxEntry(conversation.id, createdAt, draft, newest),
            draft,
            listOf(activity),
            CollaborationSpaceInboxRepairChange.Retain(intent),
        )
    return if (validCollaborationSpaceInboxPublication(proposal)) AppResult.Success(proposal)
    else
        collaborationRecoveryFailure(
            "client.collaboration_invalid_response",
            FailureKind.Unexpected,
        )
}
