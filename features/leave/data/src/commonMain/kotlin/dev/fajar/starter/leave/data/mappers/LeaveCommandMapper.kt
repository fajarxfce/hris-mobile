@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.database.CommandScope
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.leave.data.dto.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun leaveCommandScope(access: LeaveAccess) =
    CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun leaveCommandPartition(access: LeaveAccess) = "hris.leave/" + access.companyId

fun LeaveCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

fun leaveCommandAssociatedData(
    access: LeaveAccess,
    id: String,
    requestId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-leave-command-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                requestId,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

fun LeaveCommand.toDto() =
    LeaveCommandDto(
        id,
        accountId,
        audience,
        companyId,
        requestId,
        expectedVersion,
        action.name,
        reason,
        createdAt.toString(),
    )

fun LeaveCommandDto.toCommand(access: LeaveAccess): LeaveCommand {
    listOf(id, accountId, companyId, requestId).forEach { require(Uuid.parse(it).toString() == it) }
    require(expectedVersion in 0 until 9_007_199_254_740_991L)
    require(reason.isNotBlank() && reason.length <= 1_000 && reason == reason.trim())
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    return LeaveCommand(
            id,
            accountId,
            audience,
            companyId,
            requestId,
            expectedVersion,
            LeaveRequestAction.valueOf(action),
            reason,
            created,
        )
        .also { require(it.belongsTo(access)) }
}

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

fun LeaveCommandOutcomeDto.toRecord(
    command: LeaveCommand,
    raw: CommandRecord,
    access: LeaveAccess,
): LeaveCommandRecord {
    require(
        raw.partition == leaveCommandPartition(access) &&
            raw.id == command.id &&
            raw.resourceId == command.requestId &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L && attempts in 0..LEAVE_MAXIMUM_ATTEMPTS)
    require(receiptVersion == null || receiptVersion == command.expectedVersion + 1)
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
    return LeaveCommandRecord(command, raw.revision, attempts, outcome)
}

fun LeaveActionReceiptDto.toReceipt(command: LeaveCommand): LeaveActionReceipt {
    require(id == command.requestId && version == command.expectedVersion + 1)
    return LeaveActionReceipt(id, version)
}
