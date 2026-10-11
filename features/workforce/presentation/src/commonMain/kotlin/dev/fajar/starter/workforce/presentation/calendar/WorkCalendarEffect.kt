package dev.fajar.starter.workforce.presentation.calendar

sealed interface WorkCalendarEffect {
    data object Back : WorkCalendarEffect

    data object VerifyAccount : WorkCalendarEffect

    data object RecheckWorkspace : WorkCalendarEffect
}
