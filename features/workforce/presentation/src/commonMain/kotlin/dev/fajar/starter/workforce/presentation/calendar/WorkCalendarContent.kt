package dev.fajar.starter.workforce.presentation.calendar

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.workforce.domain.entities.WorkCalendarSnapshot
import dev.fajar.starter.workforce.domain.policies.calendarPeriod
import kotlinx.datetime.*

data class WorkCalendarContent(
    val snapshot: WorkCalendarSnapshot,
    val previousWeek: LocalDate?,
    val nextWeek: LocalDate?,
)

fun WorkCalendarSnapshot.toContent() =
    WorkCalendarContent(
        this,
        (calendarPeriod(directory.asOf, period.from.minus(7, DateTimeUnit.DAY))
                as? AppResult.Success)
            ?.value
            ?.from,
        (calendarPeriod(directory.asOf, period.from.plus(7, DateTimeUnit.DAY))
                as? AppResult.Success)
            ?.value
            ?.from,
    )
