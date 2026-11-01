package dev.fajar.starter.collaboration.presentation.inbox

import dev.fajar.starter.collaboration.domain.entities.CollaborationLocalList
import dev.fajar.starter.common.result.Failure

data class ChatInboxState(
    val page: CollaborationLocalList? = null,
    val after: String? = null,
    val previous: List<String?> = emptyList(),
    val remoteNext: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val failure: Failure? = null,
    val refreshFailure: Failure? = null,
) {
    val hasMore: Boolean
        get() = page?.nextCursor != null || remoteNext != null

    val restricted: Boolean
        get() = failure != null
}
