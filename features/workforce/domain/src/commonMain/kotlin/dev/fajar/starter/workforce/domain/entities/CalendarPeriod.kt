package dev.fajar.starter.workforce.domain.entities

import kotlinx.datetime.LocalDate

/** Inclusive company work dates; a mobile calendar page covers one week. */
data class CalendarPeriod(val from: LocalDate, val until: LocalDate)
