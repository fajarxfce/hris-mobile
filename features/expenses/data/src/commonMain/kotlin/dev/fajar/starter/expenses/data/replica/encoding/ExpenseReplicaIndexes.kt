package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.securestorage.contentIndexes
import kotlin.time.Instant
import kotlinx.datetime.*
import kotlinx.serialization.json.Json

fun expenseWriteCheckpoint(raw: ReplicaCheckpoint?) =
    raw?.let { "${it.handle.generation}:${it.revision}:${it.published}" }

fun expenseSummarySortKey(summary: ExpenseSummary): String {
    val epoch = summary.createdAt.toEpochMilliseconds()
    require(
        epoch in
            Instant.parse("1900-01-01T00:00:00Z").toEpochMilliseconds() until
                Instant.parse("2201-01-01T00:00:00Z").toEpochMilliseconds()
    )
    return (Instant.parse("2201-01-01T00:00:00Z").toEpochMilliseconds() - epoch)
        .toString()
        .padStart(15, '0') + "/" + summary.id
}

suspend fun expenseReplicaTag(
    key: ByteArray,
    access: ExpenseAccess,
    kind: String,
    value: String,
): String {
    val bytes =
        Json.encodeToString(
                listOf(
                    "hris-expense-index-v1",
                    access.audience,
                    access.accountId,
                    access.companyId,
                    kind,
                    value,
                )
            )
            .encodeToByteArray()
    return try {
        contentIndexes(key, listOf(bytes)).single()
    } finally {
        bytes.fill(0)
    }
}

suspend fun expenseSummaryTags(
    key: ByteArray,
    access: ExpenseAccess,
    summary: ExpenseSummary,
    timezone: String,
): Set<String> {
    val date = summary.createdAt.toLocalDateTime(TimeZone.of(timezone))
    val value = "${summary.employeeId}|${YearMonth(date.year, date.month)}"
    return setOf(
        expenseReplicaTag(key, access, "month", value),
        expenseReplicaTag(key, access, "status", "$value|${summary.status.name}"),
    )
}

suspend fun expenseSelectionTag(
    key: ByteArray,
    access: ExpenseAccess,
    selection: ExpenseSelection,
): String {
    val value = "${requireNotNull(selection.employeeId)}|${requireNotNull(selection.month)}"
    val status = selection.status
    return if (status == null) expenseReplicaTag(key, access, "month", value)
    else expenseReplicaTag(key, access, "status", "$value|${status.name}")
}

suspend fun expenseClaimTag(key: ByteArray, access: ExpenseAccess, id: String) =
    expenseReplicaTag(key, access, "claim", id)

suspend fun expenseHistoryTag(key: ByteArray, access: ExpenseAccess, id: String, after: Long) =
    expenseReplicaTag(key, access, "history", "$id|$after")

suspend fun expensePendingHistoryTag(key: ByteArray, access: ExpenseAccess) =
    expenseReplicaTag(key, access, "pending-history", "claims")
