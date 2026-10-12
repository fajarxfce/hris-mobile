@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.mappers

import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.database.CommandScope
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun overtimeCommandScope(access: WorkforceAccess) =
    CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun overtimeCommandPartition(access: WorkforceAccess) = "hris.overtime-commands/" + access.companyId

fun OvertimeCommandSlot.storageKey() =
    when (this) {
        is OvertimeCommandSlot.Plan -> "employee/$employeeId"
        is OvertimeCommandSlot.Request -> "request/$requestId"
    }

fun OvertimeCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

fun overtimeCommandAssociatedData(
    access: WorkforceAccess,
    id: String,
    resourceId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-overtime-command-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                resourceId,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

fun OvertimeCommand.toDto() =
    OvertimeCommandDto(
        id,
        accountId,
        audience,
        companyId,
        requestId,
        employeeId,
        employeeNumber,
        employmentAfter,
        workDate.toString(),
        timezone,
        when (val value = payload) {
            is OvertimeCommandPayload.Plan ->
                OvertimeCommandPayloadDto.Plan(
                    value.expectedEmploymentVersion,
                    value.requested.toDto(),
                    value.planningRevision,
                )
            is OvertimeCommandPayload.Actual ->
                OvertimeCommandPayloadDto.Actual(value.expectedVersion, value.actual.toDto())
            is OvertimeCommandPayload.Withdrawal ->
                OvertimeCommandPayloadDto.Withdrawal(value.expectedVersion)
        },
        reason,
        createdAt.toString(),
    )

fun OvertimeCommandDto.toCommand(): OvertimeCommand {
    listOf(id, accountId, companyId, requestId, employeeId).forEach {
        require(Uuid.parse(it).toString() == it)
    }
    require(
        audience.length in 1..2048 && employeeNumber.isNotBlank() && employeeNumber.length <= 32
    )
    require(
        employmentAfter == null || (employmentAfter.isNotBlank() && employmentAfter.length <= 32)
    )
    require(reason.isNotBlank() && reason.length <= 1_000 && reason == reason.trim())
    val date = LocalDate.parse(workDate)
    require(date.year in 2000..2100 && date.toString() == workDate && timezone.length in 1..100)
    TimeZone.of(timezone)
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    val input =
        when (val value = payload) {
            is OvertimeCommandPayloadDto.Plan -> {
                require(value.expectedEmploymentVersion in 0..9_007_199_254_740_991L)
                require(Regex("[0-9a-f]{64}").matches(value.planningRevision))
                OvertimeCommandPayload.Plan(
                    value.expectedEmploymentVersion,
                    value.requested.toInterval(),
                    value.planningRevision,
                )
            }
            is OvertimeCommandPayloadDto.Actual -> {
                require(value.expectedVersion in 0..998)
                OvertimeCommandPayload.Actual(value.expectedVersion, value.actual.toInterval())
            }
            is OvertimeCommandPayloadDto.Withdrawal -> {
                require(value.expectedVersion in 0..998)
                OvertimeCommandPayload.Withdrawal(value.expectedVersion)
            }
        }
    return OvertimeCommand(
        id,
        accountId,
        audience,
        companyId,
        requestId,
        employeeId,
        employeeNumber,
        employmentAfter,
        date,
        timezone,
        input,
        reason,
        created,
    )
}

fun OvertimeCommandOutcomeDto.toRecord(
    command: OvertimeCommand,
    raw: CommandRecord,
    access: WorkforceAccess,
): OvertimeCommandRecord {
    require(command.belongsTo(access))
    require(
        raw.partition == overtimeCommandPartition(access) &&
            raw.id == command.id &&
            raw.resourceId == command.slot().storageKey() &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L)
    return OvertimeCommandRecord(
        command,
        raw.revision,
        attempts,
        toOutcome(command.expectedReceiptVersion()),
    )
}

fun OvertimeInterval.toRequest() =
    OvertimeIntervalRequest(startsAt.toString(), endsAt.toString(), breakMinutes)

fun OvertimeCommandReceiptDto.toReceipt(command: OvertimeCommand): OvertimeCommandReceipt {
    require(id == command.requestId && version == command.expectedReceiptVersion())
    return OvertimeCommandReceipt(id, version)
}
