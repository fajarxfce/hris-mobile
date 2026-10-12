@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.mappers

import dev.fajar.starter.workforce.data.dto.*
import dev.fajar.starter.workforce.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.*

/** Maps one resolved or frozen day without inventing an enclosing calendar revision. */
fun WorkDayDto.toWorkDay(expectedDate: LocalDate): WorkDay {
    val date = LocalDate.parse(workDate)
    require(date == expectedDate)
    val source = origin?.let(CalendarOrigin::valueOf)
    val revision = originVersion
    require(revision == null || revision in 0..9_007_199_254_740_991L)
    holidayId?.let(Uuid::parse)
    return when (kind) {
        "WORK" -> {
            require(source == CalendarOrigin.PATTERN || source == CalendarOrigin.ROSTER)
            require(holidayId == null && revision != null)
            val shift = requireNotNull(shift).toShift()
            val start = Instant.parse(requireNotNull(startsAt))
            val end = Instant.parse(requireNotNull(endsAt))
            val zone = TimeZone.of(shift.timezone)
            val localStart = start.toLocalDateTime(zone)
            val localEnd = end.toLocalDateTime(zone)
            require(localStart.date == date && localStart.time == shift.startsAt)
            require(localEnd.time == shift.endsAt && date.daysUntil(localEnd.date) in 0..1)
            require(end > start && (end - start).inWholeMinutes <= 2880)
            require(
                plannedMinutes >= 0 &&
                    plannedMinutes == (end - start).inWholeMinutes - shift.breakMinutes
            )
            WorkDay.Work(date, shift, start, end, plannedMinutes, source, revision)
        }
        "OFF" -> {
            require(shift == null && startsAt == null && endsAt == null && plannedMinutes == 0L)
            require(source != null && revision != null)
            require((source == CalendarOrigin.HOLIDAY) == (holidayId != null))
            WorkDay.Off(date, source, revision, holidayId)
        }
        "UNASSIGNED" -> {
            require(shift == null && startsAt == null && endsAt == null && plannedMinutes == 0L)
            require(source == null && revision == null && holidayId == null)
            WorkDay.Unassigned(date)
        }
        else -> error("Unknown calendar day kind.")
    }
}

fun WorkDay.toDto(): WorkDayDto =
    when (this) {
        is WorkDay.Work ->
            WorkDayDto(
                date.toString(),
                "WORK",
                shift.toDto(),
                startsAt.toString(),
                endsAt.toString(),
                plannedMinutes,
                origin.name,
                originVersion,
            )
        is WorkDay.Off ->
            WorkDayDto(
                date.toString(),
                "OFF",
                origin = origin.name,
                originVersion = originVersion,
                holidayId = holidayId,
            )
        is WorkDay.Unassigned -> WorkDayDto(date.toString(), "UNASSIGNED")
    }
