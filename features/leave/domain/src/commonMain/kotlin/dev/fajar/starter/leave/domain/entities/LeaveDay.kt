package dev.fajar.starter.leave.domain.entities

import kotlin.time.Instant
import kotlinx.datetime.LocalDate

data class LeaveDay(
    val date: LocalDate,
    val portion: LeavePortion,
    val startsAt: Instant,
    val endsAt: Instant,
    val plannedMinutes: Int,
    val chargedMinutes: Int,
)
