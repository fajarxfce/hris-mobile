package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.sync.domain.repositories.SyncFeedRepository
import dev.fajar.starter.workforce.domain.WorkCalendarFixture
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.admitWorkforce
import dev.fajar.starter.workforce.domain.replica.entities.*
import dev.fajar.starter.workforce.domain.replica.repositories.WorkforceRecordRepository
import dev.fajar.starter.workforce.domain.replica.usecases.SyncWorkforce
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.*

class WorkforceSyncFixture(val base: WorkCalendarFixture = WorkCalendarFixture()) {
    val replicas = base.replicas
    val access =
        (admitWorkforce(
                base.workspace.id,
                base.workspace,
                base.session,
                base.build,
                base.clock.now(),
            )
                as AppResult.Success)
            .value
    val shift =
        CalendarShift(
            workforceId(10),
            1,
            "DAY",
            "Day shift",
            LocalTime(9, 0),
            LocalTime(17, 0),
            60,
            "Asia/Jakarta",
            CalendarWorkMode.ONSITE,
        )
    val schedule =
        WorkforceRecord.Schedule(
            workforceId(1),
            2,
            base.employee.id,
            LocalDate(2020, 1, 1),
            DayOfWeek.entries.associateWith { shift },
        )
    val roster =
        WorkforceRecord.Roster(
            workforceId(2),
            0,
            base.employee.id,
            base.directory.asOf,
            shift.copy(code = "NIGHT", startsAt = LocalTime(22, 0), endsAt = LocalTime(6, 0)),
        )
    val holiday =
        WorkforceRecord.Holiday(
            workforceId(3),
            0,
            base.directory.asOf.plus(1, DateTimeUnit.DAY),
            "Public holiday",
            true,
        )
    val attendance =
        WorkforceRecord.Attendance(
            workforceId(4),
            0,
            base.employee.id,
            AttendanceDay(
                LocalDate(2026, 10, 3),
                emptyList(),
                0,
                0,
                false,
                AttendanceCorrection(workforceId(5), null, null, 0),
            ),
        )
    val records =
        mutableMapOf<Pair<SyncCollection, String>, WorkforceRecord>().apply {
            listOf(schedule, roster, holiday, attendance).forEach {
                put(workforceReference(it).let { it.collection to it.id }, it)
            }
        }
    var bootstrapReads = 0
    var changesReads = 0
    var activeReads = 0
    var maximumReads = 0
    val feedRequests = mutableListOf<Triple<Set<SyncCollection>, String?, Int>>()
    val canonicalReads = mutableListOf<Pair<SyncCollection, String>>()
    var bootstrapCall: suspend (String?) -> AppResult<SyncBootstrapPage> = { cursor ->
        val start = cursor?.toInt() ?: 0
        val refs = records.values.map(::workforceReference)
        val page = refs.drop(start).take(32)
        val next = (start + page.size).takeIf { it < refs.size }?.toString()
        AppResult.Success(
            SyncBootstrapPage(
                workforceCollections,
                page,
                next,
                if (next == null) "head" else null,
                base.clock.now(),
            )
        )
    }
    var changesCall: suspend (String) -> AppResult<SyncChangePage> = { changes() }
    var canonicalCall: suspend (SyncCollection, String) -> AppResult<WorkforceRecord> =
        { collection, id ->
            AppResult.Success(requireNotNull(records[collection to id]))
        }
    val feed =
        object : SyncFeedRepository {
            override suspend fun bootstrap(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String?,
                limit: Int,
            ): AppResult<SyncBootstrapPage> {
                bootstrapReads++
                feedRequests += Triple(collections, cursor, limit)
                return bootstrapCall(cursor)
            }

            override suspend fun changes(
                sessionId: String,
                companyId: String,
                collections: Set<SyncCollection>,
                cursor: String,
                limit: Int,
            ): AppResult<SyncChangePage> {
                changesReads++
                feedRequests += Triple(collections, cursor, limit)
                return changesCall(cursor)
            }
        }
    val remote =
        object : WorkforceRecordRepository {
            override suspend fun attendance(
                access: WorkforceAccess,
                id: String,
            ): AppResult<WorkforceRecord.Attendance> =
                when (val result = read(SyncCollection.ATTENDANCE_DAYS, id)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(result.value as WorkforceRecord.Attendance)
                }

            override suspend fun schedule(
                access: WorkforceAccess,
                id: String,
            ): AppResult<WorkforceRecord.Schedule> =
                when (val result = read(SyncCollection.WORK_SCHEDULES, id)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(result.value as WorkforceRecord.Schedule)
                }

            override suspend fun roster(
                access: WorkforceAccess,
                id: String,
            ): AppResult<WorkforceRecord.Roster> =
                when (val result = read(SyncCollection.ROSTER_DAYS, id)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(result.value as WorkforceRecord.Roster)
                }

            override suspend fun holiday(
                access: WorkforceAccess,
                id: String,
            ): AppResult<WorkforceRecord.Holiday> =
                when (val result = read(SyncCollection.WORK_HOLIDAYS, id)) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(result.value as WorkforceRecord.Holiday)
                }
        }
    val execution = Mutex()
    val sync =
        SyncWorkforce(
            feed,
            remote,
            base.remote,
            replicas,
            base.sessions,
            base.workspaces,
            base.clock,
            base.build,
            execution,
        )

    private suspend fun read(collection: SyncCollection, id: String): AppResult<WorkforceRecord> {
        canonicalReads += collection to id
        activeReads++
        maximumReads = maxOf(maximumReads, activeReads)
        return try {
            canonicalCall(collection, id)
        } finally {
            activeReads--
        }
    }

    fun changes(
        items: List<SyncChange> = emptyList(),
        cursor: String = "head",
        hasMore: Boolean = false,
        pending: Boolean = false,
    ) = AppResult.Success(SyncChangePage(items, cursor, hasMore, pending, 5, base.clock.now()))
}

val workforceCollections =
    setOf(
        SyncCollection.ATTENDANCE_DAYS,
        SyncCollection.WORK_SCHEDULES,
        SyncCollection.ROSTER_DAYS,
        SyncCollection.WORK_HOLIDAYS,
    )

fun workforceId(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"

fun workforceReference(record: WorkforceRecord) =
    SyncReference(
        when (record) {
            is WorkforceRecord.Attendance -> SyncCollection.ATTENDANCE_DAYS
            is WorkforceRecord.Schedule -> SyncCollection.WORK_SCHEDULES
            is WorkforceRecord.Roster -> SyncCollection.ROSTER_DAYS
            is WorkforceRecord.Holiday -> SyncCollection.WORK_HOLIDAYS
        },
        record.id,
        record.version,
    )
