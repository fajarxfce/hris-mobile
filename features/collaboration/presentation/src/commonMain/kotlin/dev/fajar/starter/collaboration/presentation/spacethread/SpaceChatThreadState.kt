package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.Failure

/** Independent local publications: editor I/O never delays the history subscription. */
data class SpaceChatThreadState(
    val thread: CollaborationSpaceThreadSnapshot? = null,
    val composer: CollaborationSpaceComposerSnapshot? = null,
    val loading: Boolean = true,
    val historyFailure: Failure? = null,
    val composerReadFailure: Failure? = null,
    val restriction: Failure? = null,
    val draft: String = "",
    val replyToEventId: String? = null,
    val draftReady: Boolean = false,
    val draftSaving: Boolean = false,
    val draftFailure: Failure? = null,
    val provisional: List<SpaceChatPendingSubmission> = emptyList(),
    val messages: SpaceChatComposerPresentation = SpaceChatComposerPresentation(),
    val rows: List<SpaceChatMessageRow> = emptyList(),
    val progressFailure: Failure? = null,
    val projectionFailure: Failure? = null,
    val legacyPending: Boolean = false,
    val leaving: Boolean = false,
    val selection: Set<SpaceChatMessageIdentity> = emptySet(),
    val selectionLimitReached: Boolean = false,
    val actionFailure: Failure? = null,
) {
    val canCompose
        get() = draftReady && restriction == null && !legacyPending && !leaving && !selecting

    val canSend
        get() =
            canCompose &&
                projectionFailure == null &&
                draft.isNotBlank() &&
                messages.pending.size < 128 &&
                provisional.count { !it.durable } < 16

    override fun toString() = "SpaceChatThreadState([redacted])"
}

data class SpaceChatPendingSubmission(
    val original: CollaborationComposerSubmission,
    val durable: Boolean,
    val failure: Failure?,
    val record: CollaborationSpaceMessageRecord? = null,
    val reviewRequired: Boolean = false,
) {
    override fun toString() = "SpaceChatPendingSubmission([redacted])"
}
