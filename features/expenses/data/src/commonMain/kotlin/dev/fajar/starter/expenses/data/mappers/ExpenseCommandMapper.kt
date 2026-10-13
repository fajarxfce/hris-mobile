@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.mappers

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.database.CommandScope
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun expenseCommandScope(access: ExpenseAccess) =
    CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun expenseCommandPartition(access: ExpenseAccess) = "hris.expense-commands/" + access.companyId

fun ExpenseCommandSlot.storageKey() =
    when (this) {
        is ExpenseCommandSlot.Creation -> "employee/$employeeId"
        is ExpenseCommandSlot.Claim -> "claim/$claimId"
    }

fun ExpenseCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

fun expenseCommandAssociatedData(
    access: ExpenseAccess,
    id: String,
    resourceId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-expense-command-v1",
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

fun ExpenseCommand.toDto() =
    ExpenseCommandDto(
        id,
        accountId,
        audience,
        companyId,
        claimId,
        employeeId,
        employeeNumber,
        employmentAfter,
        timezone,
        when (val value = payload) {
            is ExpenseCommandPayload.SaveDraft ->
                ExpenseCommandPayloadDto.SaveDraft(
                    value.expectedVersion,
                    value.title,
                    value.description,
                    value.lines.map { it.toDto() },
                )
            is ExpenseCommandPayload.Submit ->
                ExpenseCommandPayloadDto.Submit(value.expectedVersion, value.submissionId)
            is ExpenseCommandPayload.Cancel ->
                ExpenseCommandPayloadDto.Cancel(value.expectedVersion)
            is ExpenseCommandPayload.Withdraw ->
                ExpenseCommandPayloadDto.Withdraw(value.expectedVersion, value.submissionId)
        },
        reason,
        createdAt.toString(),
    )

fun ExpenseCommandDto.toCommand(): ExpenseCommand {
    listOf(id, accountId, companyId, claimId, employeeId).forEach(::expenseId)
    require(
        audience.length in 1..2048 && employeeNumber.isNotBlank() && employeeNumber.length <= 32
    )
    require(
        employmentAfter == null || (employmentAfter.isNotBlank() && employmentAfter.length <= 32)
    )
    require(reason == reason.trim() && validateExpenseReason(reason) is AppResult.Success)
    require(timezone.length in 1..100)
    TimeZone.of(timezone)
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    val input =
        when (val value = payload) {
            is ExpenseCommandPayloadDto.SaveDraft -> {
                require(value.lines.size <= 20)
                val mapped =
                    ExpenseCommandPayload.SaveDraft(
                        value.expectedVersion,
                        value.title,
                        value.description,
                        value.lines.map { it.toLine() },
                    )
                val normalized =
                    normalizeExpenseDraft(
                        ExpenseDraftInput(mapped.title, mapped.description, mapped.lines, reason),
                        mapped.expectedVersion,
                    )
                require(normalized is AppResult.Success && normalized.value == mapped)
                mapped
            }
            is ExpenseCommandPayloadDto.Submit -> {
                require(value.expectedVersion in 0 until 9_007_199_254_740_991L)
                ExpenseCommandPayload.Submit(value.expectedVersion, expenseId(value.submissionId))
            }
            is ExpenseCommandPayloadDto.Cancel -> {
                require(value.expectedVersion in 0 until 9_007_199_254_740_991L)
                ExpenseCommandPayload.Cancel(value.expectedVersion)
            }
            is ExpenseCommandPayloadDto.Withdraw -> {
                require(value.expectedVersion in 0 until 9_007_199_254_740_991L)
                ExpenseCommandPayload.Withdraw(value.expectedVersion, expenseId(value.submissionId))
            }
        }
    return ExpenseCommand(
        id,
        accountId,
        audience,
        companyId,
        claimId,
        employeeId,
        employeeNumber,
        employmentAfter,
        timezone,
        input,
        reason,
        created,
    )
}

fun ExpenseCommandOutcomeDto.toRecord(
    command: ExpenseCommand,
    raw: CommandRecord,
    access: ExpenseAccess,
): ExpenseCommandRecord {
    require(command.belongsTo(access))
    require(
        raw.partition == expenseCommandPartition(access) &&
            raw.id == command.id &&
            raw.resourceId == command.slot().storageKey() &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L)
    return ExpenseCommandRecord(
        command,
        raw.revision,
        attempts,
        toOutcome(command.expectedReceiptVersion()),
    )
}

fun ExpenseCommandReceiptDto.toReceipt(command: ExpenseCommand): ExpenseCommandReceipt {
    require(id == command.claimId && version == command.expectedReceiptVersion())
    return ExpenseCommandReceipt(id, version)
}
