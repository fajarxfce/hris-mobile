package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.LEAVE_MAXIMUM_ATTEMPTS

fun LeaveCommandOutcome.toDto(attempts: Int): LeaveCommandOutcomeDto {
    require(attempts in 0..LEAVE_MAXIMUM_ATTEMPTS)
    return when (this) {
        LeaveCommandOutcome.Pending -> LeaveCommandOutcomeDto("PENDING", attempts)
        is LeaveCommandOutcome.Unconfirmed ->
            LeaveCommandOutcomeDto("UNCONFIRMED", attempts, failure = failure?.toCommandDto())
        is LeaveCommandOutcome.Rejected ->
            LeaveCommandOutcomeDto("REJECTED", attempts, failure = failure.toCommandDto())
        is LeaveCommandOutcome.Confirmed -> LeaveCommandOutcomeDto("CONFIRMED", attempts, version)
    }
}

private fun Failure.toCommandDto() =
    LeaveCommandFailureDto(kind.name, code, correlationId, retryAfterSeconds)

fun LeaveCommandOutcomeDto.toOutcome(expectedReceiptVersion: Long): LeaveCommandOutcome {
    require(attempts in 0..LEAVE_MAXIMUM_ATTEMPTS)
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
                "This leave action could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    val outcome =
        when (phase) {
            "PENDING" -> {
                require(attempts == 0 && receiptVersion == null && reason == null)
                LeaveCommandOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(attempts > 0 && receiptVersion == null)
                LeaveCommandOutcome.Unconfirmed(reason)
            }
            "REJECTED" -> {
                require(attempts > 0 && receiptVersion == null)
                LeaveCommandOutcome.Rejected(requireNotNull(reason))
            }
            "CONFIRMED" -> {
                require(attempts > 0 && reason == null)
                LeaveCommandOutcome.Confirmed(requireNotNull(receiptVersion))
            }
            else -> error("Unsupported leave command outcome.")
        }
    return outcome
}
