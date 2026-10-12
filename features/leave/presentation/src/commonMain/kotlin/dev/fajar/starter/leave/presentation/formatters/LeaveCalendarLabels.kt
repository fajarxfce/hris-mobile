package dev.fajar.starter.leave.presentation.formatters

import dev.fajar.starter.designsystem.components.AppDateChoice
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.localization.*
import kotlinx.datetime.*

internal fun leaveCalendarDates(
    dialog: LeaveCreateDialog.Date,
    form: LeaveCreateForm,
    language: String,
): List<AppDateChoice?> {
    val selected = if (dialog.field == LeaveDateField.FROM) form.from else form.until
    return List(dialog.month.dayOfWeek.isoDayNumber - 1) { null } +
        (1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))).map { day ->
            val date = LocalDate(dialog.month.year, dialog.month.month, day)
            AppDateChoice(
                day,
                formatNumber(day.toLong(), language),
                formatLocalDate(date.toString(), language),
                selected == date,
                dialog.field == LeaveDateField.FROM || form.from.daysUntil(date) in 0..365,
            )
        }
}

internal val leaveWeekdayLabels =
    listOf(
        AppString.WeekdayMondayShort,
        AppString.WeekdayTuesdayShort,
        AppString.WeekdayWednesdayShort,
        AppString.WeekdayThursdayShort,
        AppString.WeekdayFridayShort,
        AppString.WeekdaySaturdayShort,
        AppString.WeekdaySundayShort,
    )
