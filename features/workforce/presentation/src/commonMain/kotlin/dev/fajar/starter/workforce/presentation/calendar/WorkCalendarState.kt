package dev.fajar.starter.workforce.presentation.calendar

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workforce.domain.entities.WorkCalendarSelection
import dev.fajar.starter.workforce.domain.policies.calendarFailureInvalidatesCache

data class WorkCalendarState(
    val content: WorkCalendarContent? = null,
    val selection: WorkCalendarSelection = WorkCalendarSelection(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val cacheFailure: Failure? = null,
    val refreshFailure: Failure? = null,
    val needsVerification: Boolean = false,
) {
    val readRestricted: Boolean
        get() =
            cacheFailure?.let(::calendarFailureInvalidatesCache) == true ||
                refreshFailure?.let(::calendarFailureInvalidatesCache) == true
}
