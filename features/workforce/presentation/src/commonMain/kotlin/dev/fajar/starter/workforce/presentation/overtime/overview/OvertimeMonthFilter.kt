package dev.fajar.starter.workforce.presentation.overtime.overview

import kotlinx.datetime.*

data class OvertimeMonthFilter(
    val selected: LocalDate,
    val previous: LocalDate?,
    val next: LocalDate?,
)

data class OvertimeMonthPicker(
    val year: Int,
    val months: List<LocalDate>,
    val previousYear: Int?,
    val nextYear: Int?,
)

internal fun overtimeMonthFilter(month: LocalDate) =
    OvertimeMonthFilter(
        month,
        month.minus(1, DateTimeUnit.MONTH).takeIf { it.year >= 2000 },
        month.plus(1, DateTimeUnit.MONTH).takeIf { it.year <= 2100 },
    )

internal fun overtimeMonthPicker(year: Int) =
    OvertimeMonthPicker(
        year,
        (1..12).map { LocalDate(year, it, 1) },
        (year - 1).takeIf { it >= 2000 },
        (year + 1).takeIf { it <= 2100 },
    )
