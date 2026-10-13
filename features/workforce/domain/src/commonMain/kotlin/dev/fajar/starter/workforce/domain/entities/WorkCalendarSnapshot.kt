package dev.fajar.starter.workforce.domain.entities

import kotlin.time.Instant

/** One bounded, complete view. Saved schedules never authorize attendance capture. */
data class WorkCalendarSnapshot(
    val access: WorkforceAccess,
    val generation: String,
    val directory: WorkforceDirectory,
    val selection: WorkCalendarSelection,
    val period: CalendarPeriod,
    val calendar: WorkCalendar?,
    val fetchedAt: Instant,
    val refreshAfter: Instant,
) {
    override fun toString() = "WorkCalendarSnapshot([redacted])"
}
