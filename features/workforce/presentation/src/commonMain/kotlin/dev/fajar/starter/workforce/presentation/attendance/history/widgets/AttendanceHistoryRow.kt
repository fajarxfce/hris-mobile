package dev.fajar.starter.workforce.presentation.attendance.history.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.presentation.attendance.attendanceStamp
import dev.fajar.starter.workforce.presentation.attendance.formatters.attendanceTime
import dev.fajar.starter.workforce.presentation.attendance.history.formatters.attendanceHistoryStatusLabel
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun AttendanceHistoryRow(day: AttendanceHistoryDay, timezone: String, onClick: () -> Unit) {
    AppListRow(
        title = formatLocalDate(day.attendance.date.toString(), appLanguageTag()),
        supportingText = appString(attendanceHistoryStatusLabel(day.status)),
        onClick = onClick,
        metadata = {
            AppText(
                (attendanceStamp(AttendanceKind.CLOCK_IN, day.attendance, day.commands)?.let {
                    attendanceTime(it.capturedAt, timezone)
                } ?: "—") +
                    " – " +
                    (attendanceStamp(AttendanceKind.CLOCK_OUT, day.attendance, day.commands)?.let {
                        attendanceTime(it.capturedAt, timezone)
                    } ?: "—"),
                color = AppColors.muted,
                style = FluentTheme.typography.caption,
            )
            if (day.attendance.acceptedMinutes > 0)
                AppText(
                    calendarDuration(day.attendance.acceptedMinutes),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
        },
        trailing = { AppIcon(Icons.Regular.ChevronRight, tint = AppColors.muted) },
    )
}
