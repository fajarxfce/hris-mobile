@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data.mappers

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.CommandRecord
import dev.fajar.starter.database.CommandScope
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.inbox.data.dto.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun inboxCommandScope(access: InboxAccess) =
    CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))

fun inboxCommandPartition(access: InboxAccess) = "hris.inbox/" + access.companyId

fun InboxCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

fun inboxCommandAssociatedData(
    access: InboxAccess,
    id: String,
    itemId: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-inbox-command-v1",
                access.audience,
                access.accountId,
                access.companyId,
                id,
                itemId,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

fun InboxCommand.toDto() =
    InboxCommandDto(
        id,
        accountId,
        audience,
        companyId,
        itemId,
        expectedVersion,
        action.name,
        createdAt.toString(),
    )

fun InboxCommandDto.toCommand(access: InboxAccess): InboxCommand {
    listOf(id, accountId, companyId, itemId).forEach { require(Uuid.parse(it).toString() == it) }
    require(expectedVersion in 0 until 9_007_199_254_740_991L)
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    return InboxCommand(
            id,
            accountId,
            audience,
            companyId,
            itemId,
            expectedVersion,
            InboxAction.valueOf(action),
            created,
        )
        .also { require(it.belongsTo(access)) }
}

fun InboxCommandOutcome.toDto(attempts: Int): InboxCommandOutcomeDto {
    require(attempts in 0..INBOX_MAXIMUM_ATTEMPTS)
    return when (this) {
        InboxCommandOutcome.Pending -> InboxCommandOutcomeDto("PENDING", attempts)
        is InboxCommandOutcome.Unconfirmed ->
            InboxCommandOutcomeDto("UNCONFIRMED", attempts, failure = failure?.toCommandDto())
        is InboxCommandOutcome.Rejected ->
            InboxCommandOutcomeDto("REJECTED", attempts, failure = failure.toCommandDto())
        is InboxCommandOutcome.Confirmed -> InboxCommandOutcomeDto("CONFIRMED", attempts, version)
    }
}

private fun Failure.toCommandDto() =
    InboxCommandFailureDto(kind.name, code, correlationId, retryAfterSeconds)

fun InboxCommandOutcomeDto.toRecord(
    command: InboxCommand,
    raw: CommandRecord,
    access: InboxAccess,
): InboxCommandRecord {
    require(
        raw.partition == inboxCommandPartition(access) &&
            raw.id == command.id &&
            raw.resourceId == command.itemId &&
            raw.sortKey == command.orderKey()
    )
    require(raw.revision in 0..9_007_199_254_740_991L && attempts in 0..INBOX_MAXIMUM_ATTEMPTS)
    require(
        receiptVersion == null ||
            receiptVersion in command.expectedVersion..command.expectedVersion + 1
    )
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
                "This inbox action could not be confirmed.",
                code = it.code,
                correlationId = it.correlationId,
                retryAfterSeconds = it.retryAfterSeconds,
            )
        }
    val outcome =
        when (phase) {
            "PENDING" -> {
                require(attempts == 0 && receiptVersion == null && reason == null)
                InboxCommandOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(attempts > 0 && receiptVersion == null)
                InboxCommandOutcome.Unconfirmed(reason)
            }
            "REJECTED" -> {
                require(attempts > 0 && receiptVersion == null)
                InboxCommandOutcome.Rejected(requireNotNull(reason))
            }
            "CONFIRMED" -> {
                require(attempts > 0 && reason == null)
                InboxCommandOutcome.Confirmed(requireNotNull(receiptVersion))
            }
            else -> error("Unsupported inbox command outcome.")
        }
    return InboxCommandRecord(command, raw.revision, attempts, outcome)
}

fun InboxActionReceiptDto.toReceipt(command: InboxCommand): InboxActionReceipt {
    require(id == command.itemId && version in command.expectedVersion..command.expectedVersion + 1)
    return InboxActionReceipt(id, version)
}
