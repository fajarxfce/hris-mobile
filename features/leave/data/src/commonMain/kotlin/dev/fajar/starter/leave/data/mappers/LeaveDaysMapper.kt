package dev.fajar.starter.leave.data.mappers

import dev.fajar.starter.leave.domain.entities.LeaveDays

/** The wire contract contains decimal day strings with half-day precision. */
fun String.toLeaveDays(maximumHalfDays: Int = Int.MAX_VALUE): LeaveDays {
    require(length in 1..13 && Regex("(?:0|[1-9][0-9]{0,9})(?:\\.5)?").matches(this))
    val units = substringBefore('.').toLong() * 2 + if (endsWith(".5")) 1 else 0
    require(units in 0..maximumHalfDays.toLong())
    return LeaveDays(units.toInt())
}

fun LeaveDays.toWireDays(): String = (halfDays / 2).toString() + if (halfDays % 2 == 1) ".5" else ""
