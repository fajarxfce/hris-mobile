package dev.fajar.starter.leave.data.encoding

import dev.fajar.starter.leave.data.mappers.toSummary
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.securestorage.contentIndexes
import kotlinx.serialization.json.Json

/** Equality tokens hide employee/year/status filter values from database indexes. */
suspend fun leaveReplicaTags(
    key: ByteArray,
    access: LeaveAccess,
    subjectId: String,
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
                        subjectId,
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
            leaveSummaryTags(key, access, record.value.toSummary()).toList()
        is LeaveReplicaRecord.Balance ->
            leaveReplicaTags(
                key,
                access,
                record.employeeId,
                listOf(listOf("balances", record.value.year.toString())),
            )
    }.toSet()

suspend fun leaveSummaryTags(
    key: ByteArray,
    access: LeaveAccess,
    summary: LeaveRequestSummary,
    includeIdentity: Boolean = true,
): Set<String> {
    val filters =
        leaveReplicaTags(
            key,
            access,
            summary.employeeId,
            listOf(listOf("requests", "ALL"), listOf("requests", summary.status.name)),
        )
    return if (includeIdentity)
        (filters + leaveRequestComponentTag(key, access, summary.id, "request")).toSet()
    else filters.toSet()
}

suspend fun leaveRequestComponentTag(
    key: ByteArray,
    access: LeaveAccess,
    requestId: String,
    component: String,
): String =
    leaveReplicaTags(key, access, requestId, listOf(listOf("request-component", component)))
        .single()

suspend fun leavePendingHistoryTag(key: ByteArray, access: LeaveAccess): String =
    leaveReplicaTags(key, access, "activity", listOf(listOf("pending"))).single()
