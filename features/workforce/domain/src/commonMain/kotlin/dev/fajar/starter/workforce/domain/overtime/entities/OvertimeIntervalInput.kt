package dev.fajar.starter.workforce.domain.overtime.entities

import kotlin.time.Instant

/** Input contains no calculated duration. Application policy derives worked minutes. */
data class OvertimeIntervalInput(val startsAt: Instant, val endsAt: Instant, val breakMinutes: Int)
