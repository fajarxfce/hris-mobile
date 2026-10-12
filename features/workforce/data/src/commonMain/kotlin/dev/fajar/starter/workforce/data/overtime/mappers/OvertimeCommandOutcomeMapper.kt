package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeCommandOutcome
import dev.fajar.starter.workforce.domain.overtime.policies.OVERTIME_MAXIMUM_ATTEMPTS

fun OvertimeCommandOutcome.toDto(attempts: Int): OvertimeCommandOutcomeDto {
    require(attempts in 0..OVERTIME_MAXIMUM_ATTEMPTS)
    return when (this) {
        OvertimeCommandOutcome.Pending -> OvertimeCommandOutcomeDto("PENDING", attempts)
        is OvertimeCommandOutcome.Unconfirmed ->
            OvertimeCommandOutcomeDto("UNCONFIRMED", attempts, failure = failure?.toCommandDto())
        is OvertimeCommandOutcome.Rejected ->
            OvertimeCommandOutcomeDto("REJECTED", attempts, failure = failure.toCommandDto())
        is OvertimeCommandOutcome.Confirmed ->
            OvertimeCommandOutcomeDto("CONFIRMED", attempts, version)
    }
}

private fun Failure.toCommandDto() =
    OvertimeCommandFailureDto(kind.name, code, correlationId, retryAfterSeconds)

fun OvertimeCommandOutcomeDto.toOutcome(expectedReceiptVersion: Long): OvertimeCommandOutcome {
    require(attempts in 0..OVERTIME_MAXIMUM_ATTEMPTS)
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
                "This overtime action could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    return when (phase) {
        "PENDING" -> {
            require(attempts == 0 && receiptVersion == null && reason == null)
            OvertimeCommandOutcome.Pending
        }
        "UNCONFIRMED" -> {
            require(attempts > 0 && receiptVersion == null)
            OvertimeCommandOutcome.Unconfirmed(reason)
        }
        "REJECTED" -> {
            require(attempts > 0 && receiptVersion == null)
            OvertimeCommandOutcome.Rejected(requireNotNull(reason))
        }
        "CONFIRMED" -> {
            require(attempts > 0 && reason == null)
            OvertimeCommandOutcome.Confirmed(requireNotNull(receiptVersion))
        }
        else -> error("Unsupported overtime command outcome.")
    }
}
