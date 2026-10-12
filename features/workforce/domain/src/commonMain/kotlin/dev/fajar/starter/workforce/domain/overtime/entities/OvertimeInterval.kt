package dev.fajar.starter.workforce.domain.overtime.entities

import kotlin.time.Instant

data class OvertimeInterval(
    val startsAt: Instant,
    val endsAt: Instant,
    val breakMinutes: Int,
    val workedMinutes: Int,
)
