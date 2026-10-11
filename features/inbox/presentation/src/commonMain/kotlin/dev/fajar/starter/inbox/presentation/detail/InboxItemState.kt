package dev.fajar.starter.inbox.presentation.detail

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.presentation.list.inboxVerificationCodes

data class InboxItemState(
    val item: InboxItem? = null,
    val hasSnapshot: Boolean = false,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val pending: Boolean = false,
    val cacheFailure: Failure? = null,
    val syncFailure: Failure? = null,
    val command: InboxCommandRecord? = null,
    val actionFailure: Failure? = null,
    val commandReadFailure: Failure? = null,
    val actionsReady: Boolean = false,
    val working: Boolean = false,
    val confirmationVersion: Long? = null,
) {
    val outcomeFailure: Failure?
        get() =
            when (val outcome = command?.outcome) {
                is InboxCommandOutcome.Rejected -> outcome.failure
                is InboxCommandOutcome.Unconfirmed -> outcome.failure
                else -> null
            }

    val failure: Failure?
        get() = cacheFailure ?: commandReadFailure ?: actionFailure ?: outcomeFailure ?: syncFailure

    val needsVerification: Boolean
        get() =
            listOf(cacheFailure, commandReadFailure, actionFailure, outcomeFailure, syncFailure)
                .any { it?.code in inboxVerificationCodes }

    val canRead: Boolean
        get() =
            actionsReady &&
                !working &&
                command == null &&
                cacheFailure == null &&
                item != null &&
                item.readAt == null

    val canAcknowledge: Boolean
        get() =
            actionsReady &&
                !working &&
                command == null &&
                cacheFailure == null &&
                item?.acknowledgementRequired == true &&
                item.acknowledgedAt == null
}
