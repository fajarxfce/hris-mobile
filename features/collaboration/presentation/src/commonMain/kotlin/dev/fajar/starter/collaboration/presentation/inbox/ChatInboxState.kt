package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.CollaborationComposerMessageProgress
import dev.fajar.starter.collaboration.domain.entities.CollaborationInboxPreview
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalAccess
import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalList
import dev.fajar.starter.common.result.Failure

data class ChatInboxState(
    val page: CollaborationLocalList? = null,
    val after: String? = null,
    val previous: List<String?> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: Failure? = null,
    val refreshFailure: Failure? = null,
    val previews: Map<String, CollaborationInboxPreview> = emptyMap(),
    val previewFailure: Failure? = null,
    val unsentCounts: Map<String, Int> = emptyMap(),
    val orphanedUnsentCount: Int = 0,
    val owner: CollaborationLocalAccess? = null,
    val composerPreviews: Map<String, CollaborationComposerMessageProgress> = emptyMap(),
    val composerFailure: Failure? = null,
) {
    val hasMore: Boolean
        get() = page?.nextCursor != null

    val restricted: Boolean
        get() = failure != null
}
