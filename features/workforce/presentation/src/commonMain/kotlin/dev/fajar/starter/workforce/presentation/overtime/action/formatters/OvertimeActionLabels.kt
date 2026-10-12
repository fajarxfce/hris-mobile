package dev.fajar.starter.workforce.presentation.overtime.action.formatters

import dev.fajar.starter.designsystem.components.AppDateChoice
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import kotlinx.datetime.*

internal fun OvertimeActionKind.title(): AppString =
    when (this) {
        OvertimeActionKind.ACTUAL -> AppString.OvertimeActualTime
        OvertimeActionKind.WITHDRAW -> AppString.OvertimeWithdrawRequest
    }

internal fun OvertimeActionKind.submitLabel(): AppString =
    when (this) {
        OvertimeActionKind.ACTUAL -> AppString.OvertimeSubmitActual
        OvertimeActionKind.WITHDRAW -> AppString.OvertimeWithdrawRequest
    }

internal fun OvertimeActualField.dateLabel(): AppString =
    if (this == OvertimeActualField.START) AppString.OvertimeStartDate
    else AppString.OvertimeEndDate

internal fun OvertimeActualField.timeLabel(): AppString =
    if (this == OvertimeActualField.START) AppString.OvertimeStartTime
    else AppString.OvertimeEndTime

internal fun OvertimeActionState.actualDateBounds(): ClosedRange<LocalDate>? {
    val request = content?.value?.details?.request ?: return null
    val zone = TimeZone.of(request.timezone)
    return request.requested.startsAt.toLocalDateTime(zone).date..request.requested.endsAt
            .toLocalDateTime(zone)
            .date
}

internal fun actualCalendarDates(
    state: OvertimeActionState,
    dialog: OvertimeActionDialog.Date,
    language: String,
): List<AppDateChoice?> {
    val input = state.form?.actual ?: return emptyList()
    val bounds = state.actualDateBounds() ?: return emptyList()
    val selected =
        if (dialog.field == OvertimeActualField.START) input.startsAt.date else input.endsAt.date
    return List(dialog.month.dayOfWeek.isoDayNumber - 1) { null } +
        (1..dialog.month.daysUntil(dialog.month.plus(1, DateTimeUnit.MONTH))).map { day ->
            val date = LocalDate(dialog.month.year, dialog.month.month, day)
            AppDateChoice(
                day,
                formatNumber(day.toLong(), language),
                formatLocalDate(date.toString(), language),
                date == selected,
                date in bounds,
            )
        }
}
