package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.CollaborationDraft

fun validCollaborationDraftText(text: String): Boolean =
    text.length <= 16_384 &&
        text.none { it.isISOControl() && it !in "\n\r\t" } &&
        runCatching { text.encodeToByteArray(throwOnInvalidSequence = true).size <= 32_768 }
            .getOrDefault(false)

fun validCollaborationDraft(draft: CollaborationDraft): Boolean =
    validCollaborationMessageId(draft.conversationId) &&
        validCollaborationMessageId(draft.revision) &&
        validCollaborationDraftText(draft.text) &&
        draft.savedAt.toEpochMilliseconds() in 0 until 253_402_300_800_000L &&
        (!draft.reviewRequired || draft.submissionId != null) &&
        (draft.submissionId == null ||
            (validCollaborationMessageId(draft.submissionId) && validCollaborationText(draft.text)))
