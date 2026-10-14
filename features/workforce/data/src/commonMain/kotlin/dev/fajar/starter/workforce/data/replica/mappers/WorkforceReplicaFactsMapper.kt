package dev.fajar.starter.workforce.data.replica.mappers

import dev.fajar.starter.workforce.domain.entities.CalendarPeriod
import dev.fajar.starter.workforce.domain.replica.entities.*

/** The query includes one extra row to retain the effective rule preceding the selected range. */
fun workforceReplicaFacts(
    checkpoint: WorkforceCheckpoint,
    period: CalendarPeriod,
    records: List<WorkforceRecord>,
): WorkforceReplicaFacts {
    val attendance = records.filterIsInstance<WorkforceRecord.Attendance>()
    val schedules = records.filterIsInstance<WorkforceRecord.Schedule>()
    val roster = records.filterIsInstance<WorkforceRecord.Roster>()
    val holidays = records.filterIsInstance<WorkforceRecord.Holiday>()
    require(
        attendance.all { it.day.date >= period.from } &&
            attendance.distinctBy { it.day.date }.size == attendance.size
    )
    require(
        roster.all { it.workDate >= period.from } &&
            roster.distinctBy { it.workDate }.size == roster.size
    )
    require(
        holidays.all { it.workDate >= period.from } &&
            holidays.distinctBy { it.workDate }.size == holidays.size
    )
    require(
        schedules.all { it.effectiveFrom <= period.until } &&
            schedules.distinctBy { it.effectiveFrom }.size == schedules.size
    )
    val baseline = schedules.firstOrNull { it.effectiveFrom <= period.from }
    return WorkforceReplicaFacts(
        checkpoint,
        attendance.takeWhile { it.day.date <= period.until },
        schedules.filter { it.effectiveFrom > period.from || it == baseline },
        roster.takeWhile { it.workDate <= period.until },
        holidays.takeWhile { it.workDate <= period.until },
    )
}
