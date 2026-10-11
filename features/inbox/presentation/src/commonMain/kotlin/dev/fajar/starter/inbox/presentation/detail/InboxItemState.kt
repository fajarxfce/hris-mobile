package dev.fajar.starter.inbox.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.inbox.domain.entities.InboxItem

data class InboxItemState(
    val item: InboxItem? = null,
    val hasSnapshot: Boolean = false,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val pending: Boolean = false,
    val cacheFailure: Failure? = null,
    val syncFailure: Failure? = null,
    val needsVerification: Boolean = false,
) {
    val failure: Failure?
        get() = cacheFailure ?: syncFailure
}
