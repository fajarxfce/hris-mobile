package dev.fajar.starter.leave.domain.entities

import dev.fajar.starter.common.result.Failure

sealed interface LeaveCommandOutcome {
    data object Pending : LeaveCommandOutcome

    /** Persisted before transport. A missing response is not evidence of rollback. */
    data class Unconfirmed(val failure: Failure? = null) : LeaveCommandOutcome

    data class Rejected(val failure: Failure) : LeaveCommandOutcome

    data class Confirmed(val version: Long) : LeaveCommandOutcome
}
