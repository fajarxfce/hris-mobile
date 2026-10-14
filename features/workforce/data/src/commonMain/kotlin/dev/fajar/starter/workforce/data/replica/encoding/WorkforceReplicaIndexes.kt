package dev.fajar.starter.workforce.data.replica.encoding

import dev.fajar.starter.securestorage.contentIndexes
import dev.fajar.starter.sync.domain.entities.SyncCollection
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceRecord
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json

val workforceRecordCollections =
    listOf(
        SyncCollection.ATTENDANCE_DAYS,
        SyncCollection.WORK_SCHEDULES,
        SyncCollection.ROSTER_DAYS,
        SyncCollection.WORK_HOLIDAYS,
    )

fun workforceRecordId(collection: SyncCollection, id: String) =
    when (collection) {
        SyncCollection.ATTENDANCE_DAYS -> "attendance/$id"
        SyncCollection.WORK_SCHEDULES -> "schedule/$id"
        SyncCollection.ROSTER_DAYS -> "roster/$id"
        SyncCollection.WORK_HOLIDAYS -> "holiday/$id"
        else -> error("Unsupported workforce collection.")
    }

fun WorkforceRecord.collection() =
    when (this) {
        is WorkforceRecord.Attendance -> SyncCollection.ATTENDANCE_DAYS
        is WorkforceRecord.Schedule -> SyncCollection.WORK_SCHEDULES
        is WorkforceRecord.Roster -> SyncCollection.ROSTER_DAYS
        is WorkforceRecord.Holiday -> SyncCollection.WORK_HOLIDAYS
    }

fun WorkforceRecord.employeeId(): String? =
    when (this) {
        is WorkforceRecord.Attendance -> employeeId
        is WorkforceRecord.Schedule -> employeeId
        is WorkforceRecord.Roster -> employeeId
        is WorkforceRecord.Holiday -> null
    }

/**
 * Descending effective dates include the last rule before a requested range in one bounded read.
 */
fun workforceDateKey(collection: SyncCollection, date: LocalDate): String {
    val epoch = date.toEpochDays()
    val ordered =
        1_000_000_000_000L + if (collection == SyncCollection.WORK_SCHEDULES) -epoch else epoch
    return collection.name + "/" + ordered.toString().padStart(13, '0') + "/"
}

fun WorkforceRecord.replicaSortKey(): String =
    workforceDateKey(
        collection(),
        when (this) {
            is WorkforceRecord.Attendance -> day.date
            is WorkforceRecord.Schedule -> effectiveFrom
            is WorkforceRecord.Roster -> workDate
            is WorkforceRecord.Holiday -> workDate
        },
    ) + id

/** Employment IDs stay inside the encrypted record; indexes use account-bound equality tokens. */
suspend fun workforceReplicaTags(
    key: ByteArray,
    access: WorkforceAccess,
    employeeId: String,
): List<String> {
    val contexts =
        workforceRecordCollections.map { collection ->
            Json.encodeToString(
                    listOf(
                        "hris-workforce-index-v1",
                        access.audience,
                        access.accountId,
                        access.companyId,
                        collection.name,
                        if (collection == SyncCollection.WORK_HOLIDAYS) "company" else employeeId,
                    )
                )
                .encodeToByteArray()
        }
    return try {
        contentIndexes(key, contexts)
    } finally {
        contexts.forEach { it.fill(0) }
    }
}

suspend fun workforceRecordTag(
    key: ByteArray,
    access: WorkforceAccess,
    record: WorkforceRecord,
): String =
    workforceReplicaTags(key, access, record.employeeId() ?: "company")[
        workforceRecordCollections.indexOf(record.collection())]
