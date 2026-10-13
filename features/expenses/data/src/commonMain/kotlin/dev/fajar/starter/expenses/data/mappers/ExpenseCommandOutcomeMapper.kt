package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.ExpenseCommandOutcome
import dev.fajar.starter.expenses.domain.policies.EXPENSE_MAXIMUM_ATTEMPTS

fun ExpenseCommandOutcome.toDto(attempts: Int): ExpenseCommandOutcomeDto {
    require(attempts in 0..EXPENSE_MAXIMUM_ATTEMPTS)
    return when (this) {
        ExpenseCommandOutcome.Pending -> ExpenseCommandOutcomeDto("PENDING", attempts)
        is ExpenseCommandOutcome.Unconfirmed ->
            ExpenseCommandOutcomeDto("UNCONFIRMED", attempts, failure = failure?.toCommandDto())
        is ExpenseCommandOutcome.Rejected ->
            ExpenseCommandOutcomeDto("REJECTED", attempts, failure = failure.toCommandDto())
        is ExpenseCommandOutcome.Confirmed ->
            ExpenseCommandOutcomeDto("CONFIRMED", attempts, version)
    }
}

private fun Failure.toCommandDto() =
    ExpenseCommandFailureDto(kind.name, code, correlationId, retryAfterSeconds)

fun ExpenseCommandOutcomeDto.toOutcome(expectedReceiptVersion: Long): ExpenseCommandOutcome {
    require(attempts in 0..EXPENSE_MAXIMUM_ATTEMPTS)
    require(receiptVersion == null || receiptVersion == expectedReceiptVersion)
    val reason =
        failure?.let {
            require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
            require(
                it.correlationId == null ||
                    it.correlationId.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))
            )
            require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
            Failure(
                FailureKind.valueOf(it.kind),
                "This expense action could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    return when (phase) {
        "PENDING" -> {
            require(attempts == 0 && receiptVersion == null && reason == null)
            ExpenseCommandOutcome.Pending
        }
        "UNCONFIRMED" -> {
            require(attempts > 0 && receiptVersion == null)
            ExpenseCommandOutcome.Unconfirmed(reason)
        }
        "REJECTED" -> {
            require(attempts > 0 && receiptVersion == null)
            ExpenseCommandOutcome.Rejected(requireNotNull(reason))
        }
        "CONFIRMED" -> {
            require(attempts > 0 && reason == null)
            ExpenseCommandOutcome.Confirmed(requireNotNull(receiptVersion))
        }
        else -> error("Unsupported expense command outcome.")
    }
}
