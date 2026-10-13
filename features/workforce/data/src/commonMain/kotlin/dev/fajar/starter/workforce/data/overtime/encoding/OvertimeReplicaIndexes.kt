package dev.fajar.starter.workforce.data.overtime.encoding

import dev.fajar.starter.securestorage.contentIndexes
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import kotlinx.serialization.json.Json

/** Account-bound equality tokens keep employment and status values out of raw indexes. */
suspend fun overtimeReplicaTags(
    key: ByteArray,
    access: WorkforceAccess,
    subjectId: String,
    filters: List<List<String>>,
): List<String> {
    val contexts =
        filters.map {
            Json.encodeToString(
                    listOf(
                        "hris-overtime-index-v1",
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

suspend fun overtimeSummaryTags(
    key: ByteArray,
    access: WorkforceAccess,
    summary: OvertimeSummary,
): Set<String> =
    overtimeReplicaTags(
            key,
            access,
            summary.employeeId,
            listOf(listOf("requests", "ALL"), listOf("requests", summary.status.name)),
        )
        .toSet()

suspend fun overtimeRequestTag(
    key: ByteArray,
    access: WorkforceAccess,
    requestId: String,
    component: String,
): String =
    overtimeReplicaTags(key, access, requestId, listOf(listOf("request-component", component)))
        .single()

suspend fun overtimePendingHistoryTag(key: ByteArray, access: WorkforceAccess): String =
    overtimeReplicaTags(key, access, "activity", listOf(listOf("pending"))).single()
