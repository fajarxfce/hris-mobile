package dev.fajar.starter.inbox.domain.entities

import dev.fajar.starter.common.result.Failure

sealed interface InboxCommandOutcome {
    data object Pending : InboxCommandOutcome

    /** Persisted before transmission; a missing response never proves a rollback. */
    data class Unconfirmed(val failure: Failure? = null) : InboxCommandOutcome

    data class Rejected(val failure: Failure) : InboxCommandOutcome

    data class Confirmed(val version: Long) : InboxCommandOutcome
}
