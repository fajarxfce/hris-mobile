package dev.fajar.starter.inbox.presentation.list

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.inbox.domain.entities.InboxItem
import dev.fajar.starter.inbox.domain.policies.inboxFailureInvalidatesCache

data class InboxState(
    val items: List<InboxItem> = emptyList(),
    val hasSnapshot: Boolean = false,
    val afterCursor: String? = null,
    val nextCursor: String? = null,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val pending: Boolean = false,
    val navigating: Boolean = false,
    val cacheFailure: Failure? = null,
    val syncFailure: Failure? = null,
) {
    val readRestricted: Boolean
        get() = listOfNotNull(cacheFailure, syncFailure).any(::inboxFailureInvalidatesCache)

    val needsVerification: Boolean
        get() = listOf(cacheFailure, syncFailure).any { it?.code in inboxVerificationCodes }

    val failure: Failure?
        get() = cacheFailure ?: syncFailure
}
