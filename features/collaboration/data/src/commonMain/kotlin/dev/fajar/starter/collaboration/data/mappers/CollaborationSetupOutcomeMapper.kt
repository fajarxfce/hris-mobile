package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant

internal fun CollaborationSetupOutcome.toDto(attempts: Int): CollaborationSetupOutcomeDto {
    require(attempts in 0..1_000)
    return when (this) {
        CollaborationSetupOutcome.Pending -> CollaborationSetupOutcomeDto("PENDING", attempts)
        is CollaborationSetupOutcome.Unconfirmed ->
            CollaborationSetupOutcomeDto(
                "UNCONFIRMED",
                attempts,
                failure =
                    failure?.let {
                        CollaborationSetupFailureDto(it.kind.name, it.code, it.retryAfterSeconds)
                    },
            )
        is CollaborationSetupOutcome.Registered ->
            CollaborationSetupOutcomeDto(
                "REGISTERED",
                attempts,
                receipt = CollaborationReceiptDto(receipt.id, receipt.version),
            )
        is CollaborationSetupOutcome.Retried ->
            CollaborationSetupOutcomeDto(
                "RETRIED",
                attempts,
                receipt = CollaborationReceiptDto(receipt.id, receipt.version),
            )
        is CollaborationSetupOutcome.Bootstrapped ->
            CollaborationSetupOutcomeDto(
                "BOOTSTRAPPED",
                attempts,
                gateway =
                    CollaborationGatewayDto(
                        gateway.sessionId,
                        gateway.version,
                        gateway.matrixUserId,
                        gateway.matrixDeviceId,
                        gateway.accessToken,
                        gateway.refreshToken,
                        Instant.fromEpochMilliseconds(gateway.accessExpiresAtEpochMillis)
                            .toString(),
                        Instant.fromEpochMilliseconds(gateway.expiresAtEpochMillis).toString(),
                    ),
            )
    }
}

internal fun CollaborationSetupOutcomeDto.toOutcome(
    owner: CollaborationOwner,
    command: CollaborationSetupCommand,
): CollaborationSetupOutcome {
    require(attempts in 0..1_000)
    if (phase != "PENDING") require(attempts > 0)
    return when (phase) {
        "PENDING" -> {
            require(attempts == 0 && failure == null && receipt == null && gateway == null)
            CollaborationSetupOutcome.Pending
        }
        "UNCONFIRMED" -> {
            require(receipt == null && gateway == null)
            CollaborationSetupOutcome.Unconfirmed(failure?.toFailure())
        }
        "REGISTERED" -> {
            require(
                command.action is CollaborationSetupAction.Register &&
                    failure == null &&
                    gateway == null
            )
            CollaborationSetupOutcome.Registered(requireNotNull(receipt).toReceipt())
        }
        "RETRIED" -> {
            require(
                command.action is CollaborationSetupAction.Retry &&
                    failure == null &&
                    gateway == null
            )
            CollaborationSetupOutcome.Retried(requireNotNull(receipt).toReceipt())
        }
        "BOOTSTRAPPED" -> {
            require(failure == null && receipt == null)
            val action = command.action as CollaborationSetupAction.Bootstrap
            CollaborationSetupOutcome.Bootstrapped(
                requireNotNull(gateway)
                    .toGateway(
                        owner,
                        CollaborationBootstrap(
                            command.id,
                            action.deviceId,
                            action.deviceVersion,
                            action.gatewayVersion,
                        ),
                    )
            )
        }
        else -> error("Unsupported collaboration setup outcome.")
    }
}

private fun CollaborationSetupFailureDto.toFailure(): Failure {
    require(code == null || code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
    require(retryAfterSeconds == null || retryAfterSeconds in 0..86_400)
    return Failure(
        FailureKind.valueOf(kind),
        "Collaboration setup could not be confirmed.",
        code = code,
        retryAfterSeconds = retryAfterSeconds,
    )
}
