package dev.fajar.starter.workforce.presentation.overtime.plan.formatters

import dev.fajar.starter.designsystem.components.AppDateChoice
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import kotlinx.datetime.*

internal fun OvertimePlanDateField.label(): AppString =
    when (this) {
        OvertimePlanDateField.WORK -> AppString.OvertimeWorkDate
        OvertimePlanDateField.START -> AppString.OvertimeStartDate
        OvertimePlanDateField.END -> AppString.OvertimeEndDate
    }

internal fun OvertimeTimeField.label(): AppString =
    if (this == OvertimeTimeField.START) AppString.OvertimeStartTime else AppString.OvertimeEndTime

internal fun OvertimePlanState.planDateBounds(
    field: OvertimePlanDateField
): ClosedRange<LocalDate>? {
    val form = form ?: return null
    return if (field == OvertimePlanDateField.WORK) {
        val today = prepared?.review?.companyDate ?: form.workDate
        maxOf(LocalDate(2000, 1, 1), today.minus(31, DateTimeUnit.DAY))..minOf(
                LocalDate(2100, 12, 31),
                today.plus(90, DateTimeUnit.DAY),
            )
    } else form.workDate..minOf(LocalDate(2100, 12, 31), form.workDate.plus(2, DateTimeUnit.DAY))
}

internal fun planCalendarDates(
    state: OvertimePlanState,
    dialog: OvertimePlanDialog.Date,
    language: String,
): List<AppDateChoice?> {
    val form = state.form ?: return emptyList()
    val bounds = state.planDateBounds(dialog.field) ?: return emptyList()
    val selected =
        when (dialog.field) {
            OvertimePlanDateField.WORK -> form.workDate
            OvertimePlanDateField.START -> form.startDate
            OvertimePlanDateField.END -> form.endDate
        }
    return List(dialog.month.dayOfWeek.isoDayNumber - 1) { null } +
        (1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))).map { day ->
            val date = LocalDate(dialog.month.year, dialog.month.month, day)
            AppDateChoice(
                day,
                formatNumber(day.toLong(), language),
                formatLocalDate(date.toString(), language),
                selected == date,
                date in bounds,
            )
        }
}

internal val overtimeWeekdayLabels =
    listOf(
        AppString.WeekdayMondayShort,
        AppString.WeekdayTuesdayShort,
        AppString.WeekdayWednesdayShort,
        AppString.WeekdayThursdayShort,
        AppString.WeekdayFridayShort,
        AppString.WeekdaySaturdayShort,
        AppString.WeekdaySundayShort,
    )
