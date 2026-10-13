package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.contentIndexes
import kotlinx.serialization.json.Json

/** Equality tokens hide employee/year/status filter values from database indexes. */
suspend fun leaveReplicaTags(
    key: ByteArray,
    access: LeaveAccess,
    employeeId: String,
    filters: List<List<String>>,
): List<String> {
    val contexts =
        filters.map {
            Json.encodeToString(
                    listOf(
                        "hris-leave-index-v1",
                        access.audience,
                        access.accountId,
                        access.companyId,
                        employeeId,
                    ) + it
                )
                .encodeToByteArray()
        }
    return try {
        contentIndexes(key, contexts)
    } finally {
        contexts.forEach { it.fill(0) }
    }
}

suspend fun leaveRecordTags(
    key: ByteArray,
    access: LeaveAccess,
    record: LeaveReplicaRecord,
): Set<String> =
    when (record) {
        is LeaveReplicaRecord.Request ->
            leaveReplicaTags(
                key,
                access,
                record.value.employeeId,
                listOf(listOf("requests", "ALL"), listOf("requests", record.value.status.name)),
            )
        is LeaveReplicaRecord.Balance ->
            leaveReplicaTags(
                key,
                access,
                record.employeeId,
                listOf(listOf("balances", record.value.year.toString())),
            )
    }.toSet()
