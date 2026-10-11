package dev.fajar.starter.workforce.domain.entities

import kotlinx.datetime.LocalDate

/** Inclusive company work dates. Each acquiring use case validates its supported window. */
data class CalendarPeriod(val from: LocalDate, val until: LocalDate)
