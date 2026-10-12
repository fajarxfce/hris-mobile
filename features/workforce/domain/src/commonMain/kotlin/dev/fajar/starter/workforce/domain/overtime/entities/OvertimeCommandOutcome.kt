package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.common.result.Failure

sealed interface OvertimeCommandOutcome {
    data object Pending : OvertimeCommandOutcome

    /**
     * Persisted before transport; losing the response does not prove that the server rolled back.
     */
    data class Unconfirmed(val failure: Failure? = null) : OvertimeCommandOutcome

    data class Rejected(val failure: Failure) : OvertimeCommandOutcome

    data class Confirmed(val version: Long) : OvertimeCommandOutcome
}
